/*
 *    Fernflower - The Analytical Java Decompiler
 *    http://www.reversed-java.com
 *
 *    (C) 2008 - 2010, Stiver
 *
 *    This software is NEITHER public domain NOR free software 
 *    as per GNU License. See license.txt for more details.
 *
 *    This software is distributed WITHOUT ANY WARRANTY; without 
 *    even the implied warranty of MERCHANTABILITY or FITNESS FOR 
 *    A PARTICULAR PURPOSE. 
 */

package org.jetbrains.java.decompiler.main.rels;

import java.io.IOException;

import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.code.DeadCodeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ClearStructHelper;
import org.jetbrains.java.decompiler.modules.decompiler.DomHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ExitHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.FinallyProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.IdeaNotNullHelper;
import org.jetbrains.java.decompiler.modules.decompiler.IfHelper;
import org.jetbrains.java.decompiler.modules.decompiler.InlineSingleBlockHelper;
import org.jetbrains.java.decompiler.modules.decompiler.LabelHelper;
import org.jetbrains.java.decompiler.modules.decompiler.LoopExtractHelper;
import org.jetbrains.java.decompiler.modules.decompiler.MergeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.PPandMMHelper;
import org.jetbrains.java.decompiler.modules.decompiler.SecondaryFunctionsHelper;
import org.jetbrains.java.decompiler.modules.decompiler.SequenceHelper;
import org.jetbrains.java.decompiler.modules.decompiler.StackVarsProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.ExceptionDeobfuscator;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;

public class MethodProcessorThread implements Runnable {

	private StructMethod method; 
	private VarProcessor varproc;
	private DecompilerContext parentContext;
	
	private RootStatement root;
	
	private Throwable error;
	
	public MethodProcessorThread(StructMethod method, VarProcessor varproc, 
			DecompilerContext parentContext) {
		this.method = method;
		this.varproc = varproc;
		this.parentContext = parentContext;
	}
	
	public void run() {

		DecompilerContext.setCurrentContext(parentContext);
		
		error = null;
		root = null;
		
		try {
			root = codeToJava(method, varproc);

			synchronized(this) {
				this.notify();
			}
		
		} catch(ThreadDeath ex) {
			;
		} catch(Throwable ex) {
			error = ex;
		}
		
	}
	
	public static RootStatement codeToJava(StructMethod mt, VarProcessor varproc) throws IOException {

		StructClass cl = mt.getClassStruct();
		
		boolean isInitializer = "<clinit>".equals(mt.getName()); // for now static initializer only
		
		mt.expandData(); 
		InstructionSequence seq = mt.getInstructionSequence();			
		ControlFlowGraph graph = new ControlFlowGraph(seq);

//		System.out.println(graph.toString());
		
		
//		if(mt.getName().endsWith("_getActiveServers")) {
//			System.out.println();
//		}
		
		//DotExporter.toDotFile(graph, new File("c:\\Temp\\fern1.dot"), true);
		
		DeadCodeHelper.removeDeadBlocks(graph);
		graph.inlineJsr(mt);

//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern4.dot"), true);

		// TODO: move to the start, before jsr inlining
		DeadCodeHelper.connectDummyExitBlock(graph);
		
		DeadCodeHelper.removeGotos(graph);
		ExceptionDeobfuscator.removeCircularRanges(graph);
		//DeadCodeHelper.removeCircularRanges(graph);


//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);

		ExceptionDeobfuscator.restorePopRanges(graph);

		if(DecompilerContext.getOption(IFernflowerPreferences.REMOVE_EMPTY_RANGES)) {
			ExceptionDeobfuscator.removeEmptyRanges(graph);
		}
		
//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
		
		if(DecompilerContext.getOption(IFernflowerPreferences.NO_EXCEPTIONS_RETURN)) {
			// special case: single return instruction outside of a protected range  
			DeadCodeHelper.incorporateValueReturns(graph);
		}
		
//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern5.dot"), true);

//		ExceptionDeobfuscator.restorePopRanges(graph);
		ExceptionDeobfuscator.insertEmptyExceptionHandlerBlocks(graph);

		DeadCodeHelper.mergeBasicBlocks(graph);

		DecompilerContext.getCountercontainer().setCounter(CounterContainer.VAR_COUNTER, mt.getLocalVariables());
		
		//DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
		//System.out.println(graph.toString());
		
		if(ExceptionDeobfuscator.hasObfuscatedExceptions(graph)) {
			DecompilerContext.getLogger().writeMessage("Heavily obfuscated exception ranges found!", IFernflowerLogger.WARNING);
		}
		
		RootStatement root = DomHelper.parseGraph(graph); 
		
		if(!DecompilerContext.getOption(IFernflowerPreferences.FINALLY_CATCHALL)) {
			FinallyProcessor fproc = new FinallyProcessor(varproc); 
			while(fproc.iterateGraph(mt, root, graph)) {
				
				//DotExporter.toDotFile(graph, new File("c:\\Temp\\fern2.dot"), true);
				//System.out.println(graph.toString());

				//System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
				
				root = DomHelper.parseGraph(graph);
			}
		}

		// remove synchronized exception handler
		// not until now because of comparison between synchronized statements in the finally cycle 
		DomHelper.removeSynchronizedHandler(root);
		
//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
//		System.out.println(graph.toString());
		
//		LabelHelper.lowContinueLabels(root, new HashSet<StatEdge>());
		
		SequenceHelper.condenseSequences(root);
		
		ClearStructHelper.clearStatements(root);
		
		ExprProcessor proc = new ExprProcessor();
		proc.processStatement(root, cl);

//		DotExporter.toDotFile(graph, new File("c:\\Temp\\fern3.dot"), true);
//		System.out.println(graph.toString());

		//System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
		
		for(;;) {
			StackVarsProcessor stackproc = new StackVarsProcessor();
			stackproc.simplifyStackVars(root, mt, cl);
			
			//System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
			
			varproc.setVarVersions(root);

//			System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
			
			if(!new PPandMMHelper().findPPandMM(root)) {
				break;
			}
		}
		
		for(;;) {

			LabelHelper.cleanUpEdges(root);
			
			for(;;) {
				
				MergeHelper.enhanceLoops(root);
				
				if(LoopExtractHelper.extractLoops(root)) {
					continue;
				}
				
				if(!IfHelper.mergeAllIfs(root)) {
					break;
				}
			}
			
			if(DecompilerContext.getOption(IFernflowerPreferences.IDEA_NOT_NULL_ANNOTATION)) {
				
				if(IdeaNotNullHelper.removeHardcodedChecks(root, mt)) {
				
					SequenceHelper.condenseSequences(root);
					
					StackVarsProcessor stackproc = new StackVarsProcessor();
					stackproc.simplifyStackVars(root, mt, cl);
					
					varproc.setVarVersions(root);
				}
			}
			
			LabelHelper.identifyLabels(root);

//			System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
			
			if(InlineSingleBlockHelper.inlineSingleBlocks(root)) {
				continue;
			}
			
			// initializer may have at most one return point, so no transformation of method exits permitted 
			if(isInitializer || !ExitHelper.condenseExits(root)) {   
				break;
			}
			
			// FIXME: !!
//			if(!EliminateLoopsHelper.eliminateLoops(root)) {
//				break;
//			}
		}

		ExitHelper.removeRedundantReturns(root);
		
		SecondaryFunctionsHelper.identifySecondaryFunctions(root);
		
		varproc.setVarDefinitions(root);
		
		// must be the last invocation, because it makes the statement structure inconsistent
		// FIXME: new edge type needed
		LabelHelper.replaceContinueWithBreak(root);
		
		mt.releaseResources();

//		System.out.println("++++++++++++++++++++++/// \r\n"+root.toJava());
		
		return root;
	}

	public RootStatement getRoot() {
		return root;
	}

	public Throwable getError() {
		return error;
	}
	
}

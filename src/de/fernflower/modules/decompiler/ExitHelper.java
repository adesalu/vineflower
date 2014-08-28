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

package org.jetbrains.java.decompiler.modules.decompiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ExitExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SequenceStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;

public class ExitHelper {

	
	public static boolean condenseExits(RootStatement root) {
		
		int changed = integrateExits(root);

		if(changed > 0) {
			
			cleanUpUnreachableBlocks(root);
			
			SequenceHelper.condenseSequences(root);
		}
		
		return (changed > 0);
	}
	
	
	private static void cleanUpUnreachableBlocks(Statement stat) {
		
		boolean found; 
		do {
			
			found = false;
			
			for(int i=0;i<stat.getStats().size();i++) {
				
				Statement st = stat.getStats().get(i);
				
				cleanUpUnreachableBlocks(st);
				
				if(st.type == Statement.TYPE_SEQUENCE && st.getStats().size() > 1) {
					
					Statement last = st.getStats().getLast();
					Statement secondlast = st.getStats().get(st.getStats().size()-2);
					
					if(last.getExprents() == null || !last.getExprents().isEmpty()) {
						if(!secondlast.hasBasicSuccEdge()) {
							
							Set<Statement> set = last.getNeighboursSet(Statement.STATEDGE_DIRECT_ALL, Statement.DIRECTION_BACKWARD);
							set.remove(secondlast);
							
							if(set.isEmpty()) {
								last.setExprents(new ArrayList<Exprent>());
								found = true;
								break;
							}
						}
					}
				}
			}
			
		} while(found);
		
	}
	
	
	private static int integrateExits(Statement stat) {

		int ret = 0;
		Statement dest = null;
		
		if(stat.getExprents() == null) {
		
			for(;;) {
				
				int changed = 0;
				
				for(Statement st: stat.getStats()) {
					changed = integrateExits(st);
					if(changed > 0) {
						ret = 1;
						break;
					}
				}
				
				if(changed == 0) {
					break;
				}
			}

			
			switch(stat.type) {
			case Statement.TYPE_IF:
				IfStatement ifst = (IfStatement)stat;
				if(ifst.getIfstat() == null) {
					StatEdge ifedge = ifst.getIfEdge();
					dest = isExitEdge(ifedge);
					if(dest != null) {
						BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
								DecompilerContext.getCountercontainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
						bstat.setExprents(DecHelper.copyExprentList(dest.getExprents()));
						
						ifst.getFirst().removeSuccessor(ifedge);
						StatEdge newedge = new StatEdge(StatEdge.TYPE_REGULAR, ifst.getFirst(), bstat);
						ifst.getFirst().addSuccessor(newedge);
						ifst.setIfEdge(newedge);
						ifst.setIfstat(bstat);
						ifst.getStats().addWithKey(bstat, bstat.id);
						bstat.setParent(ifst);
						
						StatEdge oldexitedge = dest.getAllSuccessorEdges().get(0);
						StatEdge newexitedge = new StatEdge(StatEdge.TYPE_BREAK, bstat, oldexitedge.getDestination());
						bstat.addSuccessor(newexitedge);
						oldexitedge.closure.addLabeledEdge(newexitedge);
						ret = 1;
					}
				}
			}
		}
		
		
		if(stat.getAllSuccessorEdges().size() == 1 && stat.getAllSuccessorEdges().get(0).getType() == StatEdge.TYPE_BREAK && stat.getLabelEdges().isEmpty()) {
			Statement parent = stat.getParent();
			if(stat != parent.getFirst() || (parent.type != Statement.TYPE_IF && 
					parent.type != Statement.TYPE_SWITCH)) {
				
				StatEdge destedge = stat.getAllSuccessorEdges().get(0);
				dest = isExitEdge(destedge);
				if(dest != null) {
					stat.removeSuccessor(destedge);

					BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
							DecompilerContext.getCountercontainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
					bstat.setExprents(DecHelper.copyExprentList(dest.getExprents()));

					StatEdge oldexitedge = dest.getAllSuccessorEdges().get(0);
					StatEdge newexitedge = new StatEdge(StatEdge.TYPE_BREAK, bstat, oldexitedge.getDestination());
					bstat.addSuccessor(newexitedge);
					oldexitedge.closure.addLabeledEdge(newexitedge);
					
					SequenceStatement block = new SequenceStatement(Arrays.asList(new Statement[] {stat, bstat}));
					block.setAllParent();
					
					parent.replaceStatement(stat, block);
					// LabelHelper.lowContinueLabels not applicable because of forward continue edges
					// LabelHelper.lowContinueLabels(block, new HashSet<StatEdge>());
					// do it by hand
					for(StatEdge prededge : block.getPredecessorEdges(StatEdge.TYPE_CONTINUE)) {

						block.removePredecessor(prededge);
						prededge.getSource().changeEdgeNode(Statement.DIRECTION_FORWARD, prededge, stat);
						stat.addPredecessor(prededge);

						stat.addLabeledEdge(prededge);
					}
					

					stat.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, stat, bstat));
					
					for(StatEdge edge : dest.getAllPredecessorEdges()) {
						if(!edge.explicit && stat.containsStatementStrict(edge.getSource()) &&
								MergeHelper.isDirectPath(edge.getSource().getParent(), bstat)) {
							
							dest.removePredecessor(edge);
							edge.getSource().changeEdgeNode(Statement.DIRECTION_FORWARD, edge, bstat);
							bstat.addPredecessor(edge);
							
							if(!stat.containsStatementStrict(edge.closure)) {
								stat.addLabeledEdge(edge);
							}
						}
					}
					
					ret = 2;
				}
			}
		}
		
		return ret;
	}
	
	private static Statement isExitEdge(StatEdge edge) {

		Statement dest = edge.getDestination();
		
		if(edge.getType() == StatEdge.TYPE_BREAK && dest.type == Statement.TYPE_BASICBLOCK 
				&& edge.explicit && (edge.labeled || isOnlyEdge(edge))) {
			List<Exprent> data = dest.getExprents();
			
			if(data != null && data.size() == 1) {
				if(data.get(0).type == Exprent.EXPRENT_EXIT) {
					return dest;
				}
			}
		}
		
		return null;
	}	
	
	private static boolean isOnlyEdge(StatEdge edge) {
		
		Statement stat = edge.getDestination();
		
		for(StatEdge ed: stat.getAllPredecessorEdges()) {
			if(ed != edge) {
	 			if(ed.getType() == StatEdge.TYPE_REGULAR) {
	 				Statement source = ed.getSource();

	 				if(source.type == Statement.TYPE_BASICBLOCK || (source.type == Statement.TYPE_IF &&
							((IfStatement)source).iftype == IfStatement.IFTYPE_IF) ||
							(source.type == Statement.TYPE_DO && ((DoStatement)source).getLooptype() != DoStatement.LOOP_DO)) {
	 					return false;
	 				}
				} else {
					return false;
				}
			}
		}
		
		return true;
	}
	
	public static boolean removeRedundantReturns(RootStatement root) {
		
		boolean res = false;
		
		for(StatEdge edge: root.getDummyExit().getAllPredecessorEdges()) {
			if(!edge.explicit) {
				Statement source = edge.getSource();
        List<Exprent> lstExpr = source.getExprents();
        if(lstExpr != null && !lstExpr.isEmpty()) {
					Exprent expr = lstExpr.get(lstExpr.size() - 1);
					if(expr.type == Exprent.EXPRENT_EXIT) {
						ExitExprent ex = (ExitExprent)expr;
						if(ex.getExittype() == ExitExprent.EXIT_RETURN && ex.getValue() == null) {
							// remove redundant return
							lstExpr.remove(lstExpr.size() - 1);
							res = true;
						}
					}
				}
			}
		}
		
		return res;
	}
	
	public static boolean handleReturnFromInitializer(RootStatement root) {
		
		boolean res = false;
		
		Statement exit = root.getDummyExit();
		Statement top = root.getFirst();
		Statement newret = null;
		
		boolean sharedcreated = false;
		
		for(StatEdge edge: exit.getAllPredecessorEdges()) {
			if(edge.explicit) {
				
				if(!sharedcreated) {
					newret = addSharedInitializerReturn(root);
					sharedcreated = true;
				}
				
				Statement source = edge.getSource();
        List<Exprent> lstExpr = source.getExprents();
        if(lstExpr != null && !lstExpr.isEmpty()) {
					Exprent expr = lstExpr.get(lstExpr.size() - 1);
					if(expr.type == Exprent.EXPRENT_EXIT) {
						ExitExprent ex = (ExitExprent)expr;
						if(ex.getExittype() == ExitExprent.EXIT_RETURN && ex.getValue() == null) {
							lstExpr.remove(lstExpr.size() - 1);
							
							source.removeSuccessor(edge);
							source.addSuccessor(new StatEdge(StatEdge.TYPE_BREAK, source, newret, top));
							
							res = true;
						}
					}
				}
			}
		}
		
		return res; 
	}
	
	private static Statement addSharedInitializerReturn(RootStatement root) {

		Statement exit = root.getDummyExit();
		Statement top = root.getFirst();

		// build a new statement with the single instruction 'return'
		BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
				DecompilerContext.getCountercontainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));
		
		ExitExprent retexpr = new ExitExprent(ExitExprent.EXIT_RETURN, null,
				((MethodDescriptor)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_DESCRIPTOR)).ret);
		// a changeable list needed 
		bstat.setExprents(new ArrayList<Exprent>(Arrays.asList(new Exprent[]{retexpr})));
		
		// build sequence to replace the former top statement
		SequenceStatement seq = new SequenceStatement(Arrays.asList(new Statement[]{top, bstat}));
		top.setParent(seq);
		bstat.setParent(seq);
		seq.setParent(root);
		
		root.getStats().removeWithKey(top.id);
		root.getStats().addWithKeyAndIndex(0, seq, seq.id);
		root.setFirst(seq);
		
		for(StatEdge succedge : top.getAllSuccessorEdges()) {
			top.removeSuccessor(succedge);
		}
		
		top.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, top, bstat));
		bstat.addSuccessor(new StatEdge(StatEdge.TYPE_BREAK, bstat, exit, seq));
		
		return bstat; 
	}
	
	
}

package records;

public record TestRecordEmptyConstructor(int val) {
    public TestRecordEmptyConstructor {
        System.out.println(val);
    }
}


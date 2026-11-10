package app.unichat;

/**
 * A program that installs {@link Recovery} as {@code Launcher} does and then runs out of memory in a thread: the first
 * run should end and start a second one, which says how it was started and ends. Run by {@code RecoveryRestartTest}
 * as a process of its own.
 */
public final class RecoveryProbe {
    private RecoveryProbe() { }

    public static void main(String[] args) {
        Recovery.Start start = Recovery.Start.of(args);
        long previous = start.waitFor();
        start.waitForThePrevious();
        Recovery.install(start);
        if (start.restarts() == 0) {
            System.out.println("first run " + ProcessHandle.current().pid());
            System.out.flush();
            new Thread(() -> { throw new OutOfMemoryError("probe"); }, "probe").start();
            return;
        }
        boolean stillThere = ProcessHandle.of(previous).map(ProcessHandle::isAlive).orElse(false);
        System.out.println("started again after " + previous + " as restart " + start.restarts()
                + (stillThere ? " while it still ran" : " once it had ended"));
        System.out.flush();
        System.exit(0);
    }
}

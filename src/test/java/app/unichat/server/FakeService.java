package app.unichat.server;

/** Child process for SupervisorTest: "run" stays up, "crash" exits with an error at once. */
public final class FakeService {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("started");
        System.out.flush();
        if (args.length > 0 && args[0].equals("crash")) System.exit(3);
        Thread.sleep(60_000);
    }
}

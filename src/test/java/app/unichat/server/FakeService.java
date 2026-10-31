package app.unichat.server;

/**
 * Child process for SupervisorTest: "run" stays up, "crash" exits with an error at once; a second argument is printed.
 * "chatty &lt;dir&gt;" writes 1 MB to its output (far more than a pipe holds), creates dir/done, waits for dir/go and
 * prints "after".
 */
public final class FakeService {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("started");
        if (args.length > 1 && !args[0].equals("chatty")) System.out.println(args[1]);
        System.out.flush();
        if (args.length > 0 && args[0].equals("crash")) System.exit(3);
        if (args.length > 1 && args[0].equals("chatty")) {
            String line = "x".repeat(1023);
            for (int i = 0; i < 1024; i++) System.out.println(line);
            System.out.flush();
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(args[1]);
                java.nio.file.Files.writeString(dir.resolve("done"), "done");
                while (!java.nio.file.Files.exists(dir.resolve("go"))) Thread.sleep(20);
                System.out.println("after");
                System.out.flush();
                if (System.out.checkError()) System.exit(5);
            } catch (java.io.IOException e) {
                System.exit(4);
            }
        }
        Thread.sleep(60_000);
    }
}

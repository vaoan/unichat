package app.unichat.server;

/**
 * Child process for SupervisorTest: "run" stays up until its input closes (it's stopped, or the JVM that started it
 * is gone), "crash" exits with an error at once; a second argument is printed. "chatty &lt;port&gt;" writes 1 MB to
 * its output (far more than a pipe holds), connects to the test on that port to say so, waits until the test hangs
 * up and prints "after". "bridge ..." and "broken-bridge ..." are a bridge's setup commands, for BridgeInstallerTest.
 */
public final class FakeService {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].endsWith("bridge")) {
            bridgeSetup(args);
            return;
        }
        System.out.println("started");
        if (args.length > 1 && !args[0].equals("chatty")) System.out.println(args[1]);
        System.out.flush();
        if (args.length > 0 && args[0].equals("crash")) System.exit(3);
        if (args.length > 1 && args[0].equals("chatty")) {
            String line = "x".repeat(1023);
            for (int i = 0; i < 1024; i++) System.out.println(line);
            System.out.flush();
            try (java.net.Socket test = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), Integer.parseInt(args[1]))) {
                while (test.getInputStream().read() >= 0) {
                    // until the test hangs up
                }
                System.out.println("after");
                System.out.flush();
                if (System.out.checkError()) System.exit(5);
            } catch (java.io.IOException e) {
                System.exit(4);
            }
        }
        while (System.in.read() >= 0) {
            // nobody writes to it: this ends when the input closes
        }
    }

    /** Works in its current folder, like a bridge: writes what it's asked for and leaves a line per call in calls.txt. */
    private static void bridgeSetup(String[] args) throws java.io.IOException {
        java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Files.writeString(here.resolve("calls.txt"), String.join(" ", args).substring(args[0].length()).strip() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (args[0].equals("broken-bridge")) {
            System.out.println("first line");
            System.out.println("no database here");
            System.exit(7);
        }
        String flag = args[1];
        if (flag.equals("-e")) {
            java.nio.file.Files.writeString(here.resolve("config.yaml"), """
                    network:
                        some_network_key: 1
                    homeserver:
                        address: http://example.localhost:8008
                    appservice:
                        address: http://localhost:29318
                    bridge:
                        permissions:
                            example.com: user
                    database:
                        type: postgres
                    """);
        } else if (flag.equals("-g")) {
            java.nio.file.Files.writeString(here.resolve("registration.yaml"), "as_token: fake\n");
        } else {
            // A legacy bridge fills in its config, logs to a file of its own, then stops asking for the homeserver.
            java.nio.file.Files.writeString(here.resolve("config.yaml"), """
                    homeserver:
                        address: http://example.localhost:8008
                    appservice:
                        database:
                            type: postgres
                    bridge:
                        backfill:
                            forward_limits:
                                initial:
                                    dm: 0
                    """);
            java.nio.file.Files.createDirectories(here.resolve("logs"));
            java.nio.file.Files.writeString(here.resolve("logs/first.log"), "unfiltered");
            System.exit(1);
        }
    }
}

import java.io.RandomAccessFile;
import java.net.ServerSocket;

/**
 * Holds a resource like a second server would, for the lab: "port 25565" listens on the port,
 * "lock world/session.lock" takes the lock Minecraft takes on its world. Runs from source (java Hold.java).
 */
public final class Hold {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("port")) {
            try (ServerSocket socket = new ServerSocket(Integer.parseInt(args[1]))) {
                Thread.sleep(3_600_000L);
            }
        } else {
            try (RandomAccessFile file = new RandomAccessFile(args[1], "rw"); var lock = file.getChannel().lock()) {
                Thread.sleep(3_600_000L);
            }
        }
    }
}

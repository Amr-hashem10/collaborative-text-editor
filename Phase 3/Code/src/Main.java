import network.CrdtServer;
import test.CRDTTest;
import test.NetworkTest;
import test.Phase3Test;
import ui.DocumentBrowser;

import javax.swing.*;

// Entry point — browser default, w kaman server w tests lw 7abet
public class Main {

    // main — y5tar mode men args (browser default) w ysh8al server/tests/GUI
    public static void main(String[] args) throws Exception {

        String mode = (args.length > 0) ? args[0].toLowerCase() : "browser";

        switch (mode) {

            case "server" -> {
                int port = (args.length > 1) ? Integer.parseInt(args[1]) : 8080;
                CrdtServer server = new CrdtServer(port);
                server.start();
                System.out.println("Server running on port " + port + ". Press Ctrl+C to stop.");
                Thread.currentThread().join();
            }

            case "test"  -> CRDTTest.main(args);
            case "test2" -> NetworkTest.main(args);
            case "test3" -> Phase3Test.main(args);

            default -> SwingUtilities.invokeLater(() -> new DocumentBrowser().setVisible(true));
        }
    }
}

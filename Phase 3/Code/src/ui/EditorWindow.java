package ui;

import crdt.Comment;
import crdt.CrdtId;
import crdt.Document;
import crdt.Document.StyledChar;
import network.CrdtClient;
import network.CrdtClient.VersionInfo;
import operations.*;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Editor UI — JTextPane + CRDT filter + network + undo/redo + peers
public class EditorWindow extends JFrame {

    private Document   document;

    private CrdtClient client;

    private String  docId      = "";
    private String  editorCode = "";
    private String  viewerCode = "";
    private boolean viewerMode = false;

    private final JTextPane textPane;

    private final JLabel statusLabel;

    private final JLabel editorCodeLabel;
    private final JLabel viewerCodeLabel;

    private JPanel codesPanel;

    private final JButton boldBtn;
    private final JButton italicBtn;

    private final CrdtDocumentFilter filter = new CrdtDocumentFilter(); // ymane3 el typing el 3ady — el edits men el ops bas

    private boolean suppressFilter = false;

    private final Map<Integer, Integer> peerCursorOffsets = new HashMap<Integer, Integer>();

    private final Map<Integer, Object> peerHighlightTags = new HashMap<Integer, Object>();

    private static final Color[] PEER_COLORS = {
        new Color(220, 80,  80),
        new Color(60,  160, 60),
        new Color(60,  60,  220),
        new Color(200, 130, 0),
        new Color(140, 0,   200),
    };

    private int lastBroadcastOffset = -1;

    private boolean activeBold   = false;
    private boolean activeItalic = false;

    private final java.util.Set<Integer> activeUsers = new java.util.LinkedHashSet<Integer>();

    private final JPanel usersPanel = new JPanel();

    private final List<Operation> offlineBuffer = new ArrayList<>();

    private volatile boolean reconnecting = false;

    private volatile long disconnectTime = 0;

    private static final int RECONNECT_INTERVAL_MS = 3000;

    private static final long RECONNECT_TIMEOUT_MS = 5 * 60 * 1000L;

    private String lastJoinCode = "";

    private java.net.URI serverUri = null;

    private final List<Object> commentHighlightTags = new ArrayList<>();

    private javax.swing.Timer reflowTimer = null;

    // EditorWindow — JTextPane + CRDT + session UI
    public EditorWindow(int userId) {

        super("Collaborative Editor");

        document = new Document(userId);

        textPane = new JTextPane();
        textPane.setFont(new Font("Monospaced", Font.PLAIN, 14));

        textPane.setEditable(false); // l7d ma el session tet2kd — el edits men el filter bas

        ((AbstractDocument) textPane.getDocument()).setDocumentFilter(filter);

        textPane.addCaretListener(new CaretListener() {
            // caretUpdate — broadcast cursor (throttled gowa)
            @Override
            public void caretUpdate(CaretEvent e) {
                broadcastCursor();
            }
        });

        textPane.addMouseListener(new MouseAdapter() {
            // mousePressed — popup trigger comments
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showCommentContextMenu(e);
            }
            // mouseReleased — popup trigger comments
            @Override
            public void mouseReleased(MouseEvent e) {

                if (e.isPopupTrigger()) showCommentContextMenu(e);
            }
        });

        textPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            // componentResized \u2014 debounced reflow so all lines respect the new limit
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                scheduleReflow();
            }
        });

        statusLabel      = new JLabel("  Connecting\u2026");
        editorCodeLabel  = new JLabel("Editor code: \u2014");
        viewerCodeLabel  = new JLabel("Viewer code: \u2014");

        boldBtn   = new JButton("B");
        italicBtn = new JButton("I");

        buildLayout();

        setSize(1080, 680);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        addWindowListener(new java.awt.event.WindowAdapter() {
            // windowClosing — leave + flush s8yr + exit
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null) client.sendLeave();

                try { Thread.sleep(150); } catch (InterruptedException ignored) {}

                System.exit(0);
            }
        });

        setLocationRelativeTo(null);
    }

    // buildLayout — menu + north/center/south/east
    private void buildLayout() {
        setJMenuBar(buildMenuBar());
        setLayout(new BorderLayout());
        add(buildToolbar(),            BorderLayout.NORTH);
        add(new JScrollPane(textPane), BorderLayout.CENTER);
        add(buildBottomPanel(),        BorderLayout.SOUTH);
        add(buildUsersPanel(),         BorderLayout.EAST);
    }

    // buildMenuBar — File menu items
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem importItem = new JMenuItem("Import .txt\u2026");
        importItem.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — import txt
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                importDocument();
            }
        });

        JMenuItem exportItem = new JMenuItem("Export .txt\u2026");
        exportItem.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — export txt
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                exportDocument();
            }
        });

        JMenuItem renameItem = new JMenuItem("Rename\u2026");
        renameItem.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — rename doc
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                renameDocument();
            }
        });

        JMenuItem versionHistoryItem = new JMenuItem("Version History\u2026");
        versionHistoryItem.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — get_versions
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showVersionHistory();
            }
        });

        JMenuItem saveVersionItem = new JMenuItem("Save Version\u2026");
        saveVersionItem.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — save_version
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveVersion();
            }
        });

        fileMenu.add(importItem);
        fileMenu.add(exportItem);
        fileMenu.addSeparator();
        fileMenu.add(renameItem);
        fileMenu.addSeparator();
        fileMenu.add(saveVersionItem);
        fileMenu.add(versionHistoryItem);

        menuBar.add(fileMenu);
        return menuBar;
    }

    // buildUsersPanel — online list east
    private JPanel buildUsersPanel() {
        JPanel container = new JPanel(new BorderLayout());
        container.setPreferredSize(new Dimension(120, 0));
        container.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.LIGHT_GRAY));

        JLabel onlineLabel = new JLabel("Online");
        onlineLabel.setFont(onlineLabel.getFont().deriveFont(Font.BOLD, 12f));

        onlineLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        container.add(onlineLabel, BorderLayout.NORTH);

        usersPanel.setLayout(new BoxLayout(usersPanel, BoxLayout.Y_AXIS));
        container.add(new JScrollPane(usersPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);

        return container;
    }

    // buildToolbar — undo/redo + B/I
    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton undoBtn = new JButton("Undo");
        undoBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — undo
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                performUndo();
            }
        });
        bar.add(undoBtn);

        JButton redoBtn = new JButton("Redo");
        redoBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — redo
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                performRedo();
            }
        });
        bar.add(redoBtn);

        bar.addSeparator();

        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD));
        boldBtn.setToolTipText("Bold \u2014 toggles on/off for new typing; or select text first");
        boldBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — toggle bold
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggleBold();
            }
        });

        boldBtn.setEnabled(false);
        bar.add(boldBtn);

        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC));
        italicBtn.setToolTipText("Italic \u2014 toggles on/off for new typing; or select text first");
        italicBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — toggle italic
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggleItalic();
            }
        });
        italicBtn.setEnabled(false);
        bar.add(italicBtn);

        return bar;
    }

    // buildBottomPanel — status + share codes row
    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout());

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));

        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        statusBar.add(statusLabel);
        bottom.add(statusBar, BorderLayout.WEST);

        codesPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        codesPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        editorCodeLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        viewerCodeLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JButton copyEditorBtn = new JButton("Copy");
        copyEditorBtn.setMargin(new Insets(1, 4, 1, 4));
        copyEditorBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — copy editor code
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copyToClipboard(editorCode);
            }
        });

        JButton copyViewerBtn = new JButton("Copy");
        copyViewerBtn.setMargin(new Insets(1, 4, 1, 4));
        copyViewerBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — copy viewer code
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copyToClipboard(viewerCode);
            }
        });

        codesPanel.add(editorCodeLabel);
        codesPanel.add(copyEditorBtn);

        codesPanel.add(Box.createHorizontalStrut(16));
        codesPanel.add(viewerCodeLabel);
        codesPanel.add(copyViewerBtn);

        bottom.add(codesPanel, BorderLayout.EAST);
        return bottom;
    }

    // setClient — reference lel socket client
    public void setClient(CrdtClient client) {
        this.client = client;
    }

    // setConnectionInfo — URI + code lel reconnect
    public void setConnectionInfo(java.net.URI uri, String joinCode) {
        this.serverUri    = uri;
        this.lastJoinCode = joinCode;
    }

    // onSessionJoined — codes + viewer read-only UI
    public void onSessionJoined(String docId, String editorCode,
                                String viewerCode, String role, String docTitle) {
        this.docId      = docId;
        this.editorCode = editorCode;
        this.viewerCode = viewerCode;
        this.viewerMode = role.equals("viewer");

        String displayTitle = (docTitle != null && !docTitle.isEmpty()) ? docTitle : "Untitled";

        setTitle(displayTitle + " \u2014 " + (viewerMode ? "Viewer" : "Editor"));

        statusLabel.setText("  " + (viewerMode ? "Viewer" : "Connected")
                + " \u2014 doc: " + docId.substring(0, Math.min(8, docId.length())) + "\u2026");

        if (viewerMode) {

            codesPanel.removeAll();
            JLabel readOnly = new JLabel("Read-only mode");
            readOnly.setFont(new Font("Monospaced", Font.ITALIC, 12));
            readOnly.setForeground(Color.GRAY);
            codesPanel.add(readOnly);
            codesPanel.revalidate();
            codesPanel.repaint();
        } else {
            editorCodeLabel.setText("Editor code: " + editorCode);
            viewerCodeLabel.setText("Viewer code: " + viewerCode);
        }
    }

    // applyHistoryOp — applyOp bas (mesh undo stack)
    public void applyHistoryOp(Operation op) {
        try {
            document.applyOp(op);
        } catch (Exception e) {

            System.err.println("[Editor] Skipped history op: " + e.getMessage());
        }
    }

    // onHistoryDone — default block + editable + refresh
    public void onHistoryDone() {
        if (!viewerMode) {
            if (document.blockCount() == 0) {

                sendOp(document.insertBlock(-1));
            }
            textPane.setEditable(true);
            boldBtn.setEnabled(true);
            italicBtn.setEnabled(true);
        }

        refreshDisplay(0);
    }

    // applyRemoteOp — applyAndTrackUndo + refresh
    public void applyRemoteOp(Operation op) {
        try {

            document.applyAndTrackUndo(op);
        } catch (Exception e) {
            System.err.println("[Editor] Skipped remote op: " + e.getMessage());
            return;
        }

        refreshDisplay(textPane.getCaretPosition());
    }

    // sendOp — offline buffer law mesh ready
    private void sendOp(Operation op) {
        if (reconnecting || client == null || !client.isReady()) {

            offlineBuffer.add(op);
        } else {

            client.sendOp(op);
        }
    }

    // sendOps — loop sendOp
    private void sendOps(List<Operation> ops) {
        for (Operation op : ops) sendOp(op);
    }

    // onDisconnected — reconnect thread + EDT status
    public void onDisconnected() {
        if (reconnecting) return;
        reconnecting   = true;
        disconnectTime = System.currentTimeMillis();

        SwingUtilities.invokeLater(new Runnable() {
            // run — EDT reconnect label
            @Override
            public void run() {
                statusLabel.setText("  Reconnecting\u2026");
                textPane.setEditable(false);
            }
        });

        Thread t = new Thread(new Runnable() {
            // run — reconnect loop + timeout
            @Override
            public void run() {
                while (reconnecting) {
                    long elapsed = System.currentTimeMillis() - disconnectTime;

                    if (elapsed > RECONNECT_TIMEOUT_MS) {
                        reconnecting = false;
                        SwingUtilities.invokeLater(new Runnable() {
                            // run — EDT timeout message
                            @Override
                            public void run() {
                                statusLabel.setText("  Disconnected");
                                JOptionPane.showMessageDialog(EditorWindow.this,
                                        "Could not reconnect to the server after 5 minutes.\n"
                                        + "Any unsaved changes may be lost.",
                                        "Connection Lost", JOptionPane.ERROR_MESSAGE);
                            }
                        });
                        return;
                    }

                    try {
                        Thread.sleep(RECONNECT_INTERVAL_MS);
                    } catch (InterruptedException ignored) {}

                    if (serverUri == null || lastJoinCode.isEmpty()) continue;

                    if (!reconnecting) break;

                    try {
                        int uid = document.getUserId();
                        CrdtClient newClient = CrdtClient.forJoin(serverUri, lastJoinCode, uid,
                                buildSessionListener());
                        client = newClient;
                        newClient.connectBlocking();

                        Thread.sleep(5000);
                    } catch (Exception ex) {
                        System.err.println("[Editor] Reconnect attempt failed: " + ex.getMessage());
                    }
                }
            }
        }, "reconnect-thread");

        t.setDaemon(true);
        t.start();
    }

    // buildSessionListener — EDT marshalling lel callbacks
    public CrdtClient.SessionListener buildSessionListener() {
        EditorWindow self = this;
        return new CrdtClient.SessionListener() {

            // onJoined — EDT onSessionJoined
            @Override
            public void onJoined(String docId, String editorCode, String viewerCode, String role, String title) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT joined UI
                    @Override
                    public void run() {
                        onSessionJoined(docId, editorCode, viewerCode, role, title);
                    }
                });
            }

            // onHistoryOp — background thread OK
            @Override
            public void onHistoryOp(Operation op) {
                applyHistoryOp(op);
            }

            // onReady — EDT flush buffer + editable
            @Override
            public void onReady() {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — flush offline + UI
                    @Override
                    public void run() {
                        reconnecting = false;

                        if (!offlineBuffer.isEmpty()) {

                            List<Operation> toSend = new ArrayList<>(offlineBuffer);
                            offlineBuffer.clear();
                            if (client != null) client.sendOps(toSend);
                        }

                        if (!viewerMode) {
                            textPane.setEditable(true);
                            boldBtn.setEnabled(true);
                            italicBtn.setEnabled(true);
                        }
                        statusLabel.setText("  Reconnected \u2014 doc: "
                                + docId.substring(0, Math.min(8, docId.length())) + "\u2026");
                        refreshDisplay(textPane.getCaretPosition());
                    }
                });
            }

            // onRemoteOp — EDT applyRemoteOp
            @Override
            public void onRemoteOp(Operation op) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT remote op
                    @Override
                    public void run() { applyRemoteOp(op); }
                });
            }

            // onRemoteCursor — EDT cursor
            @Override
            public void onRemoteCursor(int userId, int blockIndex, int charPos) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT peer cursor
                    @Override
                    public void run() { applyRemoteCursor(userId, blockIndex, charPos); }
                });
            }

            // onUserJoined — EDT panel
            @Override
            public void onUserJoined(int userId) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT user joined
                    @Override
                    public void run() { self.onUserJoined(userId); }
                });
            }

            // onUserLeft — EDT panel
            @Override
            public void onUserLeft(int userId) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT user left
                    @Override
                    public void run() { self.onUserLeft(userId); }
                });
            }

            // onUserList — EDT refresh list
            @Override
            public void onUserList(List<Integer> userIds) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT user list
                    @Override
                    public void run() {
                        activeUsers.clear();
                        activeUsers.addAll(userIds);
                        refreshUsersPanel();
                    }
                });
            }

            // onDocumentDeleted — EDT message + dispose
            @Override
            public void onDocumentDeleted() {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT deleted
                    @Override
                    public void run() {
                        JOptionPane.showMessageDialog(self,
                                "This document has been deleted.", "Document Deleted",
                                JOptionPane.INFORMATION_MESSAGE);
                        dispose();
                    }
                });
            }

            // onDocumentRenamed — EDT title
            @Override
            public void onDocumentRenamed(String newTitle) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT rename
                    @Override
                    public void run() { self.onDocumentRenamed(newTitle); }
                });
            }

            // onDisconnected — EDT self handler
            @Override
            public void onDisconnected() {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT disconnect
                    @Override
                    public void run() { self.onDisconnected(); }
                });
            }

            // onVersionsList — EDT dialog
            @Override
            public void onVersionsList(List<VersionInfo> versions) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT versions
                    @Override
                    public void run() { showVersionHistoryDialog(versions); }
                });
            }

            // onVersionRolledBack — EDT rollback apply
            @Override
            public void onVersionRolledBack(List<Operation> ops) {
                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT rollback
                    @Override
                    public void run() { applyRollback(ops); }
                });
            }
        };
    }

    // onUserJoined — activeUsers + panel
    public void onUserJoined(int userId) {
        activeUsers.add(userId);
        refreshUsersPanel();
    }

    // onUserLeft — shel men lists + refresh
    public void onUserLeft(int userId) {
        activeUsers.remove(userId);
        peerCursorOffsets.remove(userId);
        peerHighlightTags.remove(userId);
        refreshUsersPanel();

        refreshDisplay(textPane.getCaretPosition());
    }

    // refreshUsersPanel — rebuild labels
    private void refreshUsersPanel() {
        usersPanel.removeAll();
        usersPanel.setLayout(new BoxLayout(usersPanel, BoxLayout.Y_AXIS));
        int colorIdx = 0;
        for (int uid : activeUsers) {

            Color c = PEER_COLORS[colorIdx % PEER_COLORS.length];

            JLabel lbl = new JLabel("\u25cf U" + uid);
            lbl.setForeground(c);
            lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
            lbl.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            usersPanel.add(lbl);
            colorIdx++;
        }

        usersPanel.revalidate();
        usersPanel.repaint();
    }

    // onDocumentRenamed — title + ClientStore
    public void onDocumentRenamed(String title) {
        setTitle(title + " \u2014 " + (viewerMode ? "Viewer" : "Editor"));

        db.ClientStore.saveDocument(docId, document.getUserId(), "", "", "", title, "");
    }

    // performMergeWithLimit — full merge if both fit; partial merge to fill exactly to max
    private void performMergeWithLimit(int bi, int max) {
        if (bi + 1 >= document.blockCount()) return;
        int curLen  = document.getBlockLength(bi);
        int nextLen = document.getBlockLength(bi + 1);
        if (curLen >= max) return;
        int room = max - curLen;
        if (nextLen <= room) {
            sendOps(document.mergeBlocks(bi, bi + 1));
        } else {
            sendOps(document.partialMergeBlocks(bi, bi + 1, room));
        }
    }

    // scheduleReflow — debounce resize events; fires performReflow after 250 ms of quiet
    private void scheduleReflow() {
        if (reflowTimer != null && reflowTimer.isRunning()) reflowTimer.stop();
        reflowTimer = new javax.swing.Timer(250, e -> performReflow());
        reflowTimer.setRepeats(false);
        reflowTimer.start();
    }

    // performReflow — split blocks over the limit, merge pairs that now fit; enforces 10-line cap
    private void performReflow() {
        if (viewerMode || client == null || !client.isReady()) return;
        if (document.blockCount() == 0) return;

        int max = getMaxCharsPerLine();

        // pass 1: cascade any block that exceeds the per-line limit into existing next lines.
        // Skip entirely if any single block is very long — would generate millions of ops and OOM.
        // Large-file content is left as-is; the display wraps visually via JTextPane.
        boolean changed = true;
        boolean didReflow = false;
        while (changed) {
            changed = false;
            for (int bi = 0; bi < document.blockCount(); bi++) {
                String text = document.getBlockText(bi);
                int len = text != null ? text.length() : 0;
                if (len > max) {
                    if (len > max * 40) return; // block too large to reflow safely — abort entirely
                    sendOps(document.cascadeOverflow(bi, max)); // send immediately; never accumulate
                    didReflow = true;
                    changed = true;
                    break;
                }
            }
        }

        // pass 2: merge adjacent blocks that now fit on a single line
        changed = true;
        while (changed) {
            changed = false;
            for (int bi = 0; bi < document.blockCount() - 1; bi++) {
                String t1 = document.getBlockText(bi);
                String t2 = document.getBlockText(bi + 1);
                int l1 = t1 != null ? t1.length() : 0;
                int l2 = t2 != null ? t2.length() : 0;
                if (l1 + l2 <= max) {
                    sendOps(document.mergeBlocks(bi, bi + 1));
                    didReflow = true;
                    changed = true;
                    break;
                }
            }
        }

        if (didReflow) {
            int safe = Math.min(textPane.getCaretPosition(), textPane.getDocument().getLength());
            refreshDisplay(safe);
        }
    }

    // getMaxCharsPerLine — dynamic limit from textPane width + font; windowed/fullscreen auto-adjusts
    private int getMaxCharsPerLine() {
        int w = textPane.getWidth();
        if (w <= 0) return 80;
        FontMetrics fm = textPane.getFontMetrics(textPane.getFont());
        int cw = fm.charWidth('W');
        if (cw <= 0) return 80;
        return Math.max(20, (w - 30) / cw);
    }

    // broadcastCursor — map offset -> sendCursor (throttle)
    private void broadcastCursor() {

        if (client == null || !client.isReady() || viewerMode) return;

        int offset = textPane.getCaretPosition();
        if (offset == lastBroadcastOffset) return;
        lastBroadcastOffset = offset;

        int[] pos = mapOffset(offset);
        client.sendCursor(pos[0], pos[1]);
    }

    // applyRemoteCursor — offset + highlight
    public void applyRemoteCursor(int userId, int blockIndex, int charPos) {

        int offset = mapToOffset(blockIndex, charPos);
        peerCursorOffsets.put(userId, offset);
        redrawPeerCursor(userId, offset);
    }

    // mapToOffset — blockIndex,charPos -> JTextPane offset
    private int mapToOffset(int blockIndex, int charPos) {
        int offset = 0;
        for (int i = 0; i < blockIndex; i++) {
            offset += document.getBlockLength(i) + 1;
        }
        return offset + charPos;
    }

    // redrawPeerCursor — Highlighter tag lel peer
    private void redrawPeerCursor(int userId, int offset) {
        Highlighter hl = textPane.getHighlighter();

        Object oldTag = peerHighlightTags.get(userId);
        if (oldTag != null) {
            hl.removeHighlight(oldTag);
        }

        Color color = PEER_COLORS[Math.abs(userId) % PEER_COLORS.length];

        int docLen     = textPane.getDocument().getLength();
        int safeOffset = Math.min(Math.max(0, offset), docLen);

        try {

            Object tag = hl.addHighlight(safeOffset, safeOffset,
                    new CursorHighlightPainter(color, userId));
            peerHighlightTags.put(userId, tag);
        } catch (BadLocationException e) {

        }
    }

    // clearPeerCursors — shel kol el tags
    private void clearPeerCursors() {
        Highlighter hl = textPane.getHighlighter();
        for (Object tag : peerHighlightTags.values()) {
            hl.removeHighlight(tag);
        }
        peerHighlightTags.clear();
    }

    // redrawAllPeerCursors — loop 3la map
    private void redrawAllPeerCursors() {
        for (Map.Entry<Integer, Integer> entry : peerCursorOffsets.entrySet()) {
            redrawPeerCursor(entry.getKey(), entry.getValue());
        }
    }

    // CursorHighlightPainter — vertical bar + Uid label
    private static class CursorHighlightPainter implements Highlighter.HighlightPainter {
        private final Color color;
        private final int   userId;

        // CursorHighlightPainter — color + userId
        CursorHighlightPainter(Color color, int userId) {
            this.color  = color;
            this.userId = userId;
        }

        // paint — bar + U label
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {

                Rectangle2D r = c.modelToView2D(p0);
                if (r == null) return;

                int x = (int) r.getX();
                int y = (int) r.getY();
                int h = (int) r.getHeight();

                g.setColor(color);
                g.fillRect(x, y, 2, h);

                g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
                g.drawString("U" + userId, x + 2, y + 10);
            } catch (BadLocationException e) {

            }
        }
    }

    // performUndo — undo + sendOps
    private void performUndo() {
        if (viewerMode) return;
        List<Operation> ops = document.undo();
        sendOps(ops);
        refreshDisplay(textPane.getCaretPosition());
    }

    // performRedo — redo + sendOps
    private void performRedo() {
        if (viewerMode) return;
        List<Operation> ops = document.redo();
        sendOps(ops);
        refreshDisplay(textPane.getCaretPosition());
    }

    // toggleBold — state + selection format
    private void toggleBold() {
        activeBold = !activeBold;
        updateFormatButtonAppearance();

        if (textPane.getSelectionStart() < textPane.getSelectionEnd()) {
            applyFormatToSelection(true, false);
        }

        textPane.requestFocusInWindow();
    }

    // toggleItalic — state + selection format
    private void toggleItalic() {
        activeItalic = !activeItalic;
        updateFormatButtonAppearance();

        if (textPane.getSelectionStart() < textPane.getSelectionEnd()) {
            applyFormatToSelection(false, true);
        }

        textPane.requestFocusInWindow();
    }

    // updateFormatButtonAppearance — background 3la active
    private void updateFormatButtonAppearance() {
        if (activeBold) {
            boldBtn.setBackground(new Color(180, 210, 255));
            boldBtn.setOpaque(true);
        } else {
            boldBtn.setBackground(null);
            boldBtn.setOpaque(false);
        }

        if (activeItalic) {
            italicBtn.setBackground(new Color(180, 210, 255));
            italicBtn.setOpaque(true);
        } else {
            italicBtn.setBackground(null);
            italicBtn.setOpaque(false);
        }
    }

    // applyFormatToSelection — FormatOp range + send
    private void applyFormatToSelection(boolean bold, boolean italic) {
        int selStart = textPane.getSelectionStart();
        int selEnd   = textPane.getSelectionEnd();

        if (selStart >= selEnd) {
            JOptionPane.showMessageDialog(this,
                    "Select some text first.", "Format", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int[] startMap = mapOffset(selStart);
        int[] endMap   = mapOffset(selEnd - 1);

        if (startMap[0] != endMap[0]) {
            JOptionPane.showMessageDialog(this,
                    "Formatting across paragraphs is not yet supported.",
                    "Format", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int bi      = startMap[0];
        int fromPos = startMap[1];
        int toPos   = endMap[1];

        boolean alreadySet = true;
        List<StyledChar> chars = document.getBlockStyledChars(bi);
        for (int i = fromPos; i <= toPos && i < chars.size(); i++) {
            StyledChar sc = chars.get(i);
            if (bold   && !sc.bold())   { alreadySet = false; break; }
            if (italic && !sc.italic()) { alreadySet = false; break; }
        }

        boolean apply = !alreadySet;

        List<FormatOp> ops = document.formatRange(bi, fromPos, toPos,
                bold   ? apply : chars.get(fromPos).bold(),
                italic ? apply : chars.get(fromPos).italic());

        sendOps(new ArrayList<Operation>(ops));
        refreshDisplay(selEnd);
    }

    // importDocument — parse off-EDT; O(1) insertions via insertCharAfter; no UI freeze
    private void importDocument() {

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import .txt file");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        java.io.File file = fc.getSelectedFile();
        statusLabel.setText("  Importing…");
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));

        // Each block is stored as int[][3]: {charValue, bold(1/0), italic(1/0)}
        new SwingWorker<List<int[][]>, Void>() {

            // doInBackground — read + parse file off the EDT
            @Override
            protected List<int[][]> doInBackground() throws Exception {
                List<int[][]> blocks = new ArrayList<>();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                            new java.io.FileInputStream(file),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        List<int[]> chars = new ArrayList<>();
                        boolean bold = false, italic = false;
                        int i = 0;
                        while (i < line.length()) {
                            if      (line.startsWith("<b>",  i)) { bold   = true;  i += 3; }
                            else if (line.startsWith("</b>", i)) { bold   = false; i += 4; }
                            else if (line.startsWith("<i>",  i)) { italic = true;  i += 3; }
                            else if (line.startsWith("</i>", i)) { italic = false; i += 4; }
                            else { chars.add(new int[]{line.charAt(i++), bold ? 1 : 0, italic ? 1 : 0}); }
                        }
                        if (!chars.isEmpty()) blocks.add(chars.toArray(new int[0][]));
                    }
                }
                return blocks;
            }

            // done — apply on EDT using O(1) insertCharAfter; no visible-list scan per char
            @Override
            protected void done() {
                try {
                    List<int[][]> blocks = get();
                    suppressFilter = true;

                    List<Operation> clearOps = new ArrayList<>();
                    while (document.blockCount() > 0) clearOps.add(document.deleteBlock(0));
                    sendOps(clearOps);

                    final int BATCH = 200;
                    List<Operation> batch = new ArrayList<>(BATCH);

                    for (int bi = 0; bi < blocks.size(); bi++) {
                        batch.add(document.insertBlock(bi - 1));
                        int[][] chars = blocks.get(bi);
                        CrdtId prev = null;
                        for (int[] c : chars) {
                            CharInsertOp op = document.insertCharAfter(
                                    bi, prev, (char) c[0], c[1] == 1, c[2] == 1);
                            prev = op.charId();
                            batch.add(op);
                            if (batch.size() >= BATCH) { sendOps(batch); batch.clear(); }
                        }
                    }
                    if (!batch.isEmpty()) sendOps(batch);

                    suppressFilter = false;
                    refreshDisplay(0);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(EditorWindow.this,
                            "Import failed: " + e.getMessage());
                } finally {
                    setCursor(java.awt.Cursor.getDefaultCursor());
                    statusLabel.setText("  " + (viewerMode ? "Viewer" : "Connected"));
                }
            }
        }.execute();
    }

    // exportDocument — streaming write block-by-block; no full StringBuilder in memory
    private void exportDocument() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export .txt file");
        fc.setSelectedFile(new java.io.File("document.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try (java.io.BufferedWriter bw = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(fc.getSelectedFile()),
                    java.nio.charset.StandardCharsets.UTF_8))) {

            for (int b = 0; b < document.blockCount(); b++) {
                if (b > 0) bw.write("\n\n");

                List<Document.StyledChar> chars = document.getBlockStyledChars(b);
                boolean inBold = false, inItalic = false;

                for (Document.StyledChar sc : chars) {
                    if (inItalic && !sc.italic()) { bw.write("</i>"); inItalic = false; }
                    if (inBold   && !sc.bold())   { bw.write("</b>"); inBold   = false; }
                    if (sc.bold()   && !inBold)   { bw.write("<b>");  inBold   = true;  }
                    if (sc.italic() && !inItalic) { bw.write("<i>");  inItalic = true;  }
                    bw.write(sc.value());
                }

                if (inItalic) bw.write("</i>");
                if (inBold)   bw.write("</b>");
            }

            JOptionPane.showMessageDialog(this, "Exported successfully.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
        }
    }

    // renameDocument — sendRename + local title
    private void renameDocument() {
        if (viewerMode) return;

        String currentTitle = getTitle()
                .replace(" \u2014 Editor", "")
                .replace(" \u2014 Viewer", "");

        String newTitle = JOptionPane.showInputDialog(this, "New document name:", currentTitle);
        if (newTitle == null || newTitle.trim().isEmpty()) return;
        newTitle = newTitle.trim();

        if (client != null) client.sendRename(editorCode, newTitle);

        setTitle(newTitle + " \u2014 " + (viewerMode ? "Viewer" : "Editor"));

        db.ClientStore.saveDocument(docId, document.getUserId(), "", "", "", newTitle, "");
    }

    // mapOffset — JTextPane offset -> block,char
    private int[] mapOffset(int offset) {
        int blockCount = document.blockCount();
        if (blockCount == 0) return new int[]{0, 0};

        int pos = 0;
        for (int i = 0; i < blockCount; i++) {
            int blockLen = document.getBlockLength(i);
            if (offset <= pos + blockLen) return new int[]{i, offset - pos};
            pos += blockLen + 1;
        }

        int last = blockCount - 1;
        return new int[]{last, document.getBlockLength(last)};
    }

    // refreshDisplay — rebuild StyledDocument + highlights
    void refreshDisplay(int caretPos) {
        suppressFilter = true;
        clearPeerCursors();
        try {
            StyledDocument sd = textPane.getStyledDocument();
            sd.remove(0, sd.getLength());

            int blockCount = document.blockCount();

            // collect all styled chars while building the plain text string
            List<List<StyledChar>> allBlocks = new ArrayList<>(blockCount);
            StringBuilder sb = new StringBuilder();
            for (int bi = 0; bi < blockCount; bi++) {
                if (bi > 0) sb.append('\n');
                List<StyledChar> chars = document.getBlockStyledChars(bi);
                allBlocks.add(chars);
                for (StyledChar sc : chars) sb.append(sc.value());
            }

            // one bulk insert — one Swing document event instead of N
            if (sb.length() > 0) sd.insertString(0, sb.toString(), null);

            // apply formatting in contiguous same-style runs (far fewer setCharacterAttributes calls)
            int offset = 0;
            for (int bi = 0; bi < blockCount; bi++) {
                if (bi > 0) offset++; // skip the \n separator
                List<StyledChar> chars = allBlocks.get(bi);
                int rangeStart = offset;
                boolean curBold = false, curItalic = false;
                for (int ci = 0; ci <= chars.size(); ci++) {
                    boolean newBold   = ci < chars.size() && chars.get(ci).bold();
                    boolean newItalic = ci < chars.size() && chars.get(ci).italic();
                    if (ci == chars.size() || newBold != curBold || newItalic != curItalic) {
                        if (offset > rangeStart && (curBold || curItalic)) {
                            SimpleAttributeSet a = new SimpleAttributeSet();
                            StyleConstants.setBold(a,   curBold);
                            StyleConstants.setItalic(a, curItalic);
                            sd.setCharacterAttributes(rangeStart, offset - rangeStart, a, true);
                        }
                        rangeStart = offset;
                        curBold   = newBold;
                        curItalic = newItalic;
                    }
                    if (ci < chars.size()) offset++;
                }
            }

            int safe = Math.min(Math.max(0, caretPos), sd.getLength());
            textPane.setCaretPosition(safe);

            redrawAllPeerCursors();
            refreshCommentHighlights();

        } catch (BadLocationException e) {
            e.printStackTrace();
        } finally {
            suppressFilter = false;
        }
    }

    // refreshBlock — incremental update for a single block; O(block_size) instead of O(doc_size)
    void refreshBlock(int bi, int caretPos) {
        suppressFilter = true;
        clearPeerCursors();
        try {
            StyledDocument sd = textPane.getStyledDocument();
            Element rootEl = sd.getDefaultRootElement();
            if (bi < 0 || bi >= rootEl.getElementCount()) {
                refreshDisplay(caretPos);
                return;
            }
            Element para       = rootEl.getElement(bi);
            int     paraStart  = para.getStartOffset();
            int     paraEndRaw = para.getEndOffset();
            boolean isLast     = (bi == rootEl.getElementCount() - 1);
            // getEndOffset() includes the trailing '\n' for non-last paragraphs
            int oldLen = isLast ? (paraEndRaw - paraStart) : (paraEndRaw - paraStart - 1);

            if (oldLen > 0) sd.remove(paraStart, oldLen);

            List<StyledChar> chars = document.getBlockStyledChars(bi);
            if (!chars.isEmpty()) {
                StringBuilder sb = new StringBuilder(chars.size());
                for (StyledChar sc : chars) sb.append(sc.value());
                sd.insertString(paraStart, sb.toString(), null);

                int off = paraStart, rangeStart = paraStart;
                boolean curBold = false, curItalic = false;
                for (int ci = 0; ci <= chars.size(); ci++) {
                    boolean nb = ci < chars.size() && chars.get(ci).bold();
                    boolean ni = ci < chars.size() && chars.get(ci).italic();
                    if (ci == chars.size() || nb != curBold || ni != curItalic) {
                        if (off > rangeStart && (curBold || curItalic)) {
                            SimpleAttributeSet a = new SimpleAttributeSet();
                            StyleConstants.setBold(a,   curBold);
                            StyleConstants.setItalic(a, curItalic);
                            sd.setCharacterAttributes(rangeStart, off - rangeStart, a, true);
                        }
                        rangeStart = off;
                        curBold    = nb;
                        curItalic  = ni;
                    }
                    if (ci < chars.size()) off++;
                }
            }

            int safe = Math.min(Math.max(0, caretPos), sd.getLength());
            textPane.setCaretPosition(safe);
            redrawAllPeerCursors();
            refreshCommentHighlights();
        } catch (BadLocationException e) {
            refreshDisplay(caretPos);
        } finally {
            suppressFilter = false;
        }
    }

    // CrdtDocumentFilter — typing -> CRDT ops
    private class CrdtDocumentFilter extends DocumentFilter {

        // insertString — handleInsert law mesh suppressed
        @Override
        public void insertString(FilterBypass fb, int offset, String str,
                                 AttributeSet attr) throws BadLocationException {

            if (suppressFilter) { fb.insertString(offset, str, attr); return; }
            handleInsert(offset, str);
        }

        // replace — remove then insert
        @Override
        public void replace(FilterBypass fb, int offset, int length, String text,
                            AttributeSet attrs) throws BadLocationException {

            if (suppressFilter) { fb.replace(offset, length, text, attrs); return; }
            if (length > 0)                     handleRemove(offset, length);
            if (text != null && !text.isEmpty()) handleInsert(offset, text);
        }

        // remove — handleRemove
        @Override
        public void remove(FilterBypass fb, int offset,
                           int length) throws BadLocationException {
            if (suppressFilter) { fb.remove(offset, length); return; }
            handleRemove(offset, length);
        }

        // handleInsert — chars + newline split + auto-split on line overflow
        private void handleInsert(int startOffset, String str) {
            if (str == null || str.isEmpty()) return;

            if (document.blockCount() == 0) sendOp(document.insertBlock(-1));

            int blocksBefore  = document.blockCount();
            int currentOffset = startOffset;
            int lastBi        = -1;

            for (int i = 0; i < str.length(); i++) {
                char  ch  = str.charAt(i);
                int[] pos = mapOffset(currentOffset);
                int   bi  = pos[0], cp = pos[1];

                if (ch == '\n') {
                    sendOps(document.splitBlock(bi, cp - 1));
                } else {
                    sendOp(document.insertChar(bi, cp, ch, activeBold, activeItalic));
                    lastBi = bi;
                    int max = getMaxCharsPerLine();
                    if (document.getBlockLength(bi) > max) {
                        sendOps(document.cascadeOverflow(bi, max));
                    }
                }
                currentOffset++;
            }

            if (document.blockCount() != blocksBefore || lastBi < 0) {
                refreshDisplay(currentOffset);
            } else {
                refreshBlock(lastBi, currentOffset);
            }
        }

        // handleRemove — reverse loop removeSingle; incremental refresh for single-char deletes
        private void handleRemove(int offset, int length) {
            if (length <= 0) return;
            int blocksBefore = document.blockCount();
            int bi0          = mapOffset(offset)[0]; // block of first affected char
            for (int i = length - 1; i >= 0; i--) removeSingle(offset + i);
            if (document.blockCount() != blocksBefore || length > 1) {
                refreshDisplay(offset);
            } else {
                refreshBlock(bi0, offset);
            }
        }

        // removeSingle — delete char or merge at boundary
        private void removeSingle(int offset) {
            if (document.blockCount() == 0) return;
            int[] pos      = mapOffset(offset);
            int   bi       = pos[0], cp = pos[1];
            int   blockLen = document.getBlockLength(bi);
            int   max      = getMaxCharsPerLine();
            if (cp >= blockLen) {
                performMergeWithLimit(bi, max);
            } else {
                sendOp(document.deleteChar(bi, cp));
                performMergeWithLimit(bi, max);
            }
        }
    }

    // copyToClipboard — StringSelection + toast
    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(text), null);
        JOptionPane.showMessageDialog(this,
                "Copied: " + text, "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    // showCommentContextMenu — comments + block move/copy
    private void showCommentContextMenu(MouseEvent e) {

        int offset = textPane.viewToModel2D(e.getPoint());
        if (offset < 0) return;

        int[] pos = mapOffset(offset);
        int bi = pos[0];
        int cp = pos[1];

        JPopupMenu menu = new JPopupMenu();

        Comment existing = document.getCommentAt(bi, cp);

        if (viewerMode) {

            if (existing == null) return;

            JMenuItem info = new JMenuItem("\uD83D\uDCAC " + existing.text() + "  \u2014  U" + existing.authorId());
            info.setEnabled(false);
            menu.add(info);

        } else {

            if (existing != null) {
                JMenuItem info = new JMenuItem("\"" + existing.text() + "\" \u2014 U" + existing.authorId());
                info.setEnabled(false);
                menu.add(info);
                menu.addSeparator();
            }

            JMenuItem addItem = new JMenuItem("Add Comment\u2026");
            addItem.addActionListener(new java.awt.event.ActionListener() {
                // actionPerformed — AddCommentOp
                @Override
                public void actionPerformed(java.awt.event.ActionEvent ev) {
                    String text = JOptionPane.showInputDialog(EditorWindow.this,
                            "Enter comment:", "Add Comment", JOptionPane.PLAIN_MESSAGE);
                    if (text == null || text.trim().isEmpty()) return;
                    try {
                        AddCommentOp op = document.addComment(bi, cp, text.trim());
                        sendOp(op);
                        refreshDisplay(textPane.getCaretPosition());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(EditorWindow.this,
                                "Could not add comment: " + ex.getMessage());
                    }
                }
            });
            menu.add(addItem);

            if (existing != null) {
                JMenuItem removeItem = new JMenuItem("Remove Comment");
                removeItem.addActionListener(new java.awt.event.ActionListener() {
                    // actionPerformed — RemoveCommentOp
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent ev) {
                        RemoveCommentOp op = document.removeComment(existing.commentId());
                        if (op != null) sendOp(op);
                        refreshDisplay(textPane.getCaretPosition());
                    }
                });
                menu.add(removeItem);
            }
        }

        if (!viewerMode) {
            menu.addSeparator();

            int totalBlocks = document.blockCount();

            JMenuItem moveUpItem = new JMenuItem("Move Block Up");
            moveUpItem.setEnabled(bi > 0);
            moveUpItem.addActionListener(new java.awt.event.ActionListener() {
                // actionPerformed — moveBlock up
                @Override
                public void actionPerformed(java.awt.event.ActionEvent ev) {

                    int toAfter = bi - 2;
                    List<Operation> ops = document.moveBlock(bi, toAfter);
                    sendOps(ops);
                    refreshDisplay(textPane.getCaretPosition());
                }
            });
            menu.add(moveUpItem);

            JMenuItem moveDownItem = new JMenuItem("Move Block Down");
            moveDownItem.setEnabled(bi < totalBlocks - 1);
            moveDownItem.addActionListener(new java.awt.event.ActionListener() {
                // actionPerformed — moveBlock down
                @Override
                public void actionPerformed(java.awt.event.ActionEvent ev) {

                    List<Operation> ops = document.moveBlock(bi, bi + 1);
                    sendOps(ops);
                    refreshDisplay(textPane.getCaretPosition());
                }
            });
            menu.add(moveDownItem);

            JMenuItem copyBlockItem = new JMenuItem("Copy Block Below");
            copyBlockItem.addActionListener(new java.awt.event.ActionListener() {
                // actionPerformed — copyBlock ta7t
                @Override
                public void actionPerformed(java.awt.event.ActionEvent ev) {

                    List<Operation> ops = document.copyBlock(bi, bi);
                    sendOps(ops);
                    refreshDisplay(textPane.getCaretPosition());
                }
            });
            menu.add(copyBlockItem);
        }

        menu.show(textPane, e.getX(), e.getY());
    }

    // refreshCommentHighlights — yellow line + char
    private void refreshCommentHighlights() {
        Highlighter hl = textPane.getHighlighter();

        for (Object tag : commentHighlightTags) {
            hl.removeHighlight(tag);
        }
        commentHighlightTags.clear();

        Highlighter.HighlightPainter linePainter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 200, 80));

        Highlighter.HighlightPainter charPainter =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 210, 0, 200));

        int offset = 0;
        for (int bi = 0; bi < document.blockCount(); bi++) {
            if (bi > 0) offset++;

            Map<Integer, Comment> blockComments = document.getCommentsForBlock(bi);

            if (!blockComments.isEmpty()) {
                String txt      = document.getBlockText(bi);
                int    blockLen = (txt != null ? txt.length() : 0);

                int lineStart = offset;
                int lineEnd   = offset + Math.max(blockLen, 1);

                try {
                    Object tag = hl.addHighlight(lineStart, lineEnd, linePainter);
                    commentHighlightTags.add(tag);
                } catch (BadLocationException ignored) {}

                for (Map.Entry<Integer, Comment> entry : blockComments.entrySet()) {
                    int charOffset = offset + entry.getKey();
                    try {
                        Object tag = hl.addHighlight(charOffset, charOffset + 1, charPainter);
                        commentHighlightTags.add(tag);
                    } catch (BadLocationException ignored) {}
                }

                offset += blockLen;
            } else {

                String txt = document.getBlockText(bi);
                offset += (txt != null ? txt.length() : 0);
            }
        }
    }

    // saveVersion — dialog + sendSaveVersion
    private void saveVersion() {
        if (viewerMode) {
            JOptionPane.showMessageDialog(this, "Viewers cannot save versions.");
            return;
        }

        String label = JOptionPane.showInputDialog(this, "Version label:", "Save Version",
                JOptionPane.PLAIN_MESSAGE);

        if (label == null || label.trim().isEmpty()) return;

        if (client != null) client.sendSaveVersion(editorCode, label.trim());
        JOptionPane.showMessageDialog(this, "Version saved.");
    }

    // showVersionHistory — sendGetVersions
    private void showVersionHistory() {

        String code = viewerMode ? viewerCode : editorCode;
        if (code.isEmpty()) return;
        if (client != null) client.sendGetVersions(code);

    }

    // showVersionHistoryDialog — list + rollback button
    void showVersionHistoryDialog(List<VersionInfo> versions) {
        if (versions.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No saved versions yet.\nUse File \u203a Save Version\u2026 to create one.",
                    "Version History", JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        JDialog dialog = new JDialog(this, "Version History", true);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.setSize(420, 280);
        dialog.setLocationRelativeTo(this);

        DefaultListModel<VersionInfo> model = new DefaultListModel<>();
        for (VersionInfo v : versions) model.addElement(v);

        JList<VersionInfo> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);

        JButton rollbackBtn = new JButton("Rollback to Selected");
        rollbackBtn.setEnabled(!viewerMode);
        rollbackBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — sendRollback
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                VersionInfo selected = list.getSelectedValue();
                if (selected == null) return;

                int confirm = JOptionPane.showConfirmDialog(dialog,
                        "Roll back to \"" + selected.label + "\"?\n"
                        + "This will reset the document for ALL collaborators.",
                        "Confirm Rollback", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) return;

                if (client != null) client.sendRollback(editorCode, selected.id);
                dialog.dispose();
            }
        });

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — close dialog
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) { dialog.dispose(); }
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(rollbackBtn);
        btnPanel.add(closeBtn);

        dialog.add(new JLabel("  Select a version to roll back to:"), BorderLayout.NORTH);
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    // applyRollback — doc gedeed + replay ops
    void applyRollback(List<Operation> ops) {

        document = new Document(document.getUserId());

        for (Operation op : ops) {
            try {
                document.applyOp(op);
            } catch (Exception e) {
                System.err.println("[Editor] Skipped rollback op: " + e.getMessage());
            }
        }

        if (!viewerMode && document.blockCount() == 0) {
            sendOp(document.insertBlock(-1));
        }

        statusLabel.setText("  Rolled back \u2014 doc: "
                + docId.substring(0, Math.min(8, docId.length())) + "\u2026");

        refreshDisplay(0);

        JOptionPane.showMessageDialog(this,
                "Document rolled back to selected version.", "Rollback",
                JOptionPane.INFORMATION_MESSAGE);
    }
}

package ui;

import db.ClientStore;
import db.ClientStore.ClientDocumentRecord;
import network.CrdtClient;
import network.CrdtClient.SessionListener;
import network.CrdtClient.VersionInfo;
import operations.Operation;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.List;

// El screen el awlani: create, join, w reopen men el recent. Swing callbacks men el socket lazem truh 3la EDT (invokeLater ta7t).
public class DocumentBrowser extends JFrame {

    // Default server lw el user mashy keda bla ma yktb 7aga
    private static final String DEFAULT_SERVER = "ws://localhost:8080";

    // El data el wara2 el JList — add/remove by7sal auto refresh
    private final DefaultListModel<ClientDocumentRecord> recentModel =
            new DefaultListModel<ClientDocumentRecord>();

    // DocumentBrowser — recent list + create/join
    public DocumentBrowser() {
        super("Collaborative Editor");
        buildLayout();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(480, 360));
    }

    // buildLayout — title + buttons + recent JList
    private void buildLayout() {

        JLabel title = new JLabel("Collaborative Editor");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setBorder(BorderFactory.createEmptyBorder(20, 0, 12, 0));
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JButton createBtn = new JButton("Create New Document");
        JButton joinBtn   = new JButton("Join with Share Code");
        JButton rejoinBtn = new JButton("Open Selected");
        JButton deleteBtn = new JButton("Remove from List");
        rejoinBtn.setEnabled(false);
        deleteBtn.setEnabled(false);

        // final 3shan el anonymous listeners: "this" mesh el browser, self hya
        final DocumentBrowser self = this;

        createBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — showCreateDialog
            @Override public void actionPerformed(ActionEvent e) { self.showCreateDialog(); }
        });

        joinBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — showJoinDialog
            @Override public void actionPerformed(ActionEvent e) { self.showJoinDialog(); }
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        btnPanel.add(createBtn);
        btnPanel.add(joinBtn);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(title,    BorderLayout.NORTH);
        topPanel.add(btnPanel, BorderLayout.SOUTH);

        JLabel recentLabel = new JLabel("Recent Documents");
        recentLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 0));

        final JList<ClientDocumentRecord> recentList =
                new JList<ClientDocumentRecord>(recentModel);
        recentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        recentList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            // valueChanged — enable rejoin/delete
            @Override
            public void valueChanged(javax.swing.event.ListSelectionEvent e2) {
                boolean selected = !recentList.isSelectionEmpty();
                rejoinBtn.setEnabled(selected);
                deleteBtn.setEnabled(selected);
            }
        });

        recentList.addMouseListener(new MouseAdapter() {
            // mouseClicked — double-click yft7
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !recentList.isSelectionEmpty()) {
                    openFromRecent(recentList.getSelectedValue());
                }
            }
        });

        rejoinBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — openFromRecent
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientDocumentRecord rec = recentList.getSelectedValue();
                if (rec != null) self.openFromRecent(rec);
            }
        });

        deleteBtn.addActionListener(new java.awt.event.ActionListener() {
            // actionPerformed — local remove aw server delete
            @Override
            public void actionPerformed(ActionEvent e) {
                ClientDocumentRecord rec = recentList.getSelectedValue();
                if (rec == null) return;

                // 3 options: local bs, server kman, w cancel — 3ady
                Object[] options = {"Remove locally only", "Delete from server too", "Cancel"};
                int choice = JOptionPane.showOptionDialog(
                        self,
                        "Remove \"" + rec.title + "\" from the recent list?\n"
                        + "You can also permanently delete it from the server.",
                        "Remove Document",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[0]);

                if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

                if (choice == 1) {

                    deleteFromServer(rec);
                }

                ClientStore.removeDocument(rec.docId);
                recentModel.removeElement(rec);
                rejoinBtn.setEnabled(false);
                deleteBtn.setEnabled(false);
            }
        });

        JScrollPane scroll = new JScrollPane(recentList);
        scroll.setPreferredSize(new Dimension(440, 200));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        centerPanel.add(recentLabel, BorderLayout.NORTH);
        centerPanel.add(scroll,      BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 6));
        bottomPanel.add(deleteBtn);
        bottomPanel.add(rejoinBtn);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        root.add(topPanel,    BorderLayout.NORTH);
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);
        setContentPane(root);

        loadRecentDocuments();
    }

    // loadRecentDocuments — men ClientStore lel model
    void loadRecentDocuments() {
        recentModel.clear();
        List<ClientDocumentRecord> docs = ClientStore.getRecentDocuments();
        for (ClientDocumentRecord rec : docs) {
            recentModel.addElement(rec);
        }
    }

    // deleteFromServer — thread: temp client delete
    private void deleteFromServer(final ClientDocumentRecord rec) {
        if (rec.editorCode == null || rec.editorCode.isEmpty()) return;
        if (rec.serverUrl  == null || rec.serverUrl.isEmpty())  return;

        Thread t = new Thread(new Runnable() {
            // run — connect + delete + close
            @Override
            public void run() {
                try {
                    URI uri = new URI(rec.serverUrl);

                    SessionListener noopListener = new SessionListener() {
                        // onJoined — noop delete client
                        @Override
                        public void onJoined(String docId, String editorCode,
                                             String viewerCode, String role, String title) {}
                        // onHistoryOp — noop
                        @Override
                        public void onHistoryOp(Operation op) {}
                        // onReady — noop
                        @Override
                        public void onReady() {}
                        // onRemoteOp — noop
                        @Override
                        public void onRemoteOp(Operation op) {}
                    };

                    DeleteClient tempClient = new DeleteClient(uri, rec.editorCode, noopListener);
                    tempClient.connectBlocking();
                    Thread.sleep(500);
                    tempClient.closeBlocking();
                } catch (Exception ex) {
                    System.err.println("[Browser] Could not delete from server: " + ex.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // DeleteClient — WebSocket yeb3at delete bas
    private static class DeleteClient extends org.java_websocket.client.WebSocketClient {

        private final String editorCode;
        private final SessionListener listener;

        // DeleteClient — URI + editorCode + listener
        DeleteClient(URI serverUri, String editorCode, SessionListener listener) {
            super(serverUri);
            this.editorCode = editorCode;
            this.listener   = listener;
        }

        // onOpen — send delete JSON
        @Override
        public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) {
            send("{\"action\":\"delete\",\"code\":\"" + editorCode + "\"}");
        }

        // onMessage — ignored
        @Override
        public void onMessage(String message) {

        }

        // onClose — ignored
        @Override
        public void onClose(int code, String reason, boolean remote) {

        }

        // onError — log
        @Override
        public void onError(Exception ex) {
            System.err.println("[DeleteClient] Error: " + ex.getMessage());
        }
    }

    // showCreateDialog — inputs + openConnection create
    private void showCreateDialog() {
        JTextField serverField = new JTextField(DEFAULT_SERVER, 22);
        JTextField titleField  = new JTextField("", 22);
        JTextField userField   = new JTextField("1", 6);

        JPanel panel = new JPanel(new GridLayout(3, 2, 6, 6));
        panel.add(new JLabel("Server URL:"));
        panel.add(serverField);
        panel.add(new JLabel("Document title:"));
        panel.add(titleField);
        panel.add(new JLabel("Your User ID:"));
        panel.add(userField);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Create New Document", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            int    userId   = Integer.parseInt(userField.getText().trim());
            URI    uri      = new URI(serverField.getText().trim());
            String docTitle = titleField.getText().trim();
            if (docTitle.isEmpty()) docTitle = "Untitled";
            openConnection(uri, null, userId, docTitle);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Invalid input: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // showJoinDialog — code + openConnection join
    private void showJoinDialog() {
        JTextField serverField = new JTextField(DEFAULT_SERVER, 22);
        JTextField codeField   = new JTextField("", 10);
        JTextField userField   = new JTextField("2", 6);

        JPanel panel = new JPanel(new GridLayout(3, 2, 6, 6));
        panel.add(new JLabel("Server URL:"));
        panel.add(serverField);
        panel.add(new JLabel("Share Code:"));
        panel.add(codeField);
        panel.add(new JLabel("Your User ID:"));
        panel.add(userField);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Join Document", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        String code = codeField.getText().trim().toUpperCase();
        if (code.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a share code.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            int userId = Integer.parseInt(userField.getText().trim());
            URI uri    = new URI(serverField.getText().trim());
            openConnection(uri, code, userId, null);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Invalid input: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // openFromRecent — dialog user/server + openConnection
    private void openFromRecent(ClientDocumentRecord rec) {

        String savedUrl = (rec.serverUrl != null && !rec.serverUrl.isEmpty())
                ? rec.serverUrl : null;

        JTextField userField   = new JTextField(rec.userId > 0 ? String.valueOf(rec.userId) : "1", 6);
        JTextField serverField = new JTextField(savedUrl != null ? savedUrl : DEFAULT_SERVER, 22);

        JPanel panel;
        if (savedUrl == null) {
            panel = new JPanel(new GridLayout(2, 2, 6, 6));
            panel.add(new JLabel("Server URL:"));
            panel.add(serverField);
            panel.add(new JLabel("Your User ID:"));
            panel.add(userField);
        } else {
            panel = new JPanel(new GridLayout(1, 2, 6, 6));
            panel.add(new JLabel("Your User ID:"));
            panel.add(userField);
        }

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Rejoin: " + rec.title, JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            int    userId    = Integer.parseInt(userField.getText().trim());
            String urlString = (savedUrl != null) ? savedUrl : serverField.getText().trim();
            URI    uri       = new URI(urlString);

            if (savedUrl == null) {
                ClientStore.saveDocument(rec.docId, rec.userId, rec.joinCode,
                        rec.editorCode, rec.viewerCode, rec.title, urlString);
            }

            String joinCode;
            if (userId == rec.userId && !rec.joinCode.isEmpty()) {

                joinCode = rec.joinCode;
            } else {

                ClientDocumentRecord existing = ClientStore.findRecord(rec.docId, userId);
                if (existing != null && !existing.joinCode.isEmpty()) {
                    joinCode = existing.joinCode;
                } else {

                    JOptionPane.showMessageDialog(this,
                            "User " + userId + " has not joined \"" + rec.title + "\" before.\n"
                            + "Use \"Join with Share Code\" to join, or create a new document.",
                            "Unknown User",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            openConnection(uri, joinCode, userId, rec.title);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not connect: " + ex.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // openConnection — EditorWindow + SessionListener + connect
    private void openConnection(final URI uri, final String code,
                                final int userId, final String docTitle) {

        final EditorWindow editor = new EditorWindow(userId);
        final DocumentBrowser browser = this;
        final String serverUrl = uri.toString();

        SessionListener listener = new SessionListener() {

            // onJoined — save ClientStore + EDT show editor
            @Override
            public void onJoined(final String docId, final String editorCode,
                                 final String viewerCode, final String role, final String title) {

                final String resolvedTitle = (title != null && !title.isEmpty()) ? title
                        : (docTitle != null && !docTitle.isEmpty()) ? docTitle : "Untitled";

                final String joinCode = role.equals("editor") ? editorCode : viewerCode;
                ClientStore.saveDocument(docId, userId, joinCode,
                        editorCode, viewerCode, resolvedTitle, serverUrl);

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT joined
                    @Override
                    public void run() {
                        editor.onSessionJoined(docId, editorCode, viewerCode, role, resolvedTitle);
                        editor.setVisible(true);
                        browser.setVisible(false);
                    }
                });
            }

            // onHistoryOp — EDT history op
            @Override
            public void onHistoryOp(final Operation op) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT applyHistoryOp
                    @Override
                    public void run() { editor.applyHistoryOp(op); }
                });
            }

            // onReady — EDT onHistoryDone
            @Override
            public void onReady() {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT ready
                    @Override
                    public void run() { editor.onHistoryDone(); }
                });
            }

            // onRemoteOp — EDT remote op
            @Override
            public void onRemoteOp(final Operation op) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT applyRemoteOp
                    @Override
                    public void run() { editor.applyRemoteOp(op); }
                });
            }

            // onRemoteCursor — EDT cursor
            @Override
            public void onRemoteCursor(final int userId, final int blockIndex,
                                       final int charPos) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT applyRemoteCursor
                    @Override
                    public void run() { editor.applyRemoteCursor(userId, blockIndex, charPos); }
                });
            }

            // onUserJoined — EDT
            @Override
            public void onUserJoined(final int userId) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT onUserJoined
                    @Override
                    public void run() { editor.onUserJoined(userId); }
                });
            }

            // onUserLeft — EDT
            @Override
            public void onUserLeft(final int userId) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT onUserLeft
                    @Override
                    public void run() { editor.onUserLeft(userId); }
                });
            }

            // onUserList — EDT seed users
            @Override
            public void onUserList(final List<Integer> userIds) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT user list
                    @Override
                    public void run() {
                        for (int uid : userIds) {
                            editor.onUserJoined(uid);
                        }
                    }
                });
            }

            // onDocumentDeleted — EDT back lel browser
            @Override
            public void onDocumentDeleted() {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT deleted UI
                    @Override
                    public void run() {
                        JOptionPane.showMessageDialog(editor,
                                "This document was deleted by the owner.");
                        editor.dispose();
                        browser.setVisible(true);
                        browser.loadRecentDocuments();
                    }
                });
            }

            // onDocumentRenamed — EDT
            @Override
            public void onDocumentRenamed(final String newTitle) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT rename
                    @Override
                    public void run() { editor.onDocumentRenamed(newTitle); }
                });
            }

            // onDisconnected — EDT
            @Override
            public void onDisconnected() {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT disconnect
                    @Override
                    public void run() { editor.onDisconnected(); }
                });
            }

            // onVersionsList — EDT dialog
            @Override
            public void onVersionsList(final List<VersionInfo> versions) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT versions
                    @Override
                    public void run() { editor.showVersionHistoryDialog(versions); }
                });
            }

            // onVersionRolledBack — EDT rollback
            @Override
            public void onVersionRolledBack(final List<Operation> ops) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT rollback
                    @Override
                    public void run() { editor.applyRollback(ops); }
                });
            }

            // onError — EDT warning + dispose
            @Override
            public void onError(final String message) {

                SwingUtilities.invokeLater(new Runnable() {
                    // run — EDT error
                    @Override
                    public void run() {
                        editor.dispose();
                        browser.setVisible(true);
                        JOptionPane.showMessageDialog(browser, message,
                                "Cannot Join", JOptionPane.WARNING_MESSAGE);
                    }
                });
            }
        };

        CrdtClient client;
        if (code == null) {
            String titleToSend = (docTitle != null && !docTitle.isEmpty()) ? docTitle : "Untitled";
            client = CrdtClient.forCreate(uri, userId, titleToSend, listener);
        } else {
            client = CrdtClient.forJoin(uri, code, userId, listener);
        }

        editor.setClient(client);

        editor.setConnectionInfo(uri, code != null ? code : "");

        try {
            client.connect();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(browser,
                    "Could not connect: " + ex.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}

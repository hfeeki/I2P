package net.i2p.netmonitor.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

class NetViewerGUI extends JFrame {
    private NetViewer _viewer;
    private NetViewerPlotPane _plotPane;
    private NetViewerControlPane _controlPane;
    private final static Color WHITE = new Color(255, 255, 255);
    private Color _background = WHITE;
    
    /**
     * Creates the GUI for all youz who be too shoopid for text based shitz
     * @param monitor the monitor the gui operates over
     */
    public NetViewerGUI(NetViewer viewer) {
        super("Network Viewer");
        _viewer = viewer;
        initializeComponents();
        pack();
        //setResizable(false);
        setVisible(true);
    }
    
    NetViewer getViewer() { return _viewer; }
    
    /** build up all our widgets */
    private void initializeComponents() {
        getContentPane().setLayout(new BorderLayout());
        
        setBackground(_background);
        
        _plotPane = new JFreeChartHeartbeatPlotPane(this); // // new NetViewerPlotPane(this); // 
        _plotPane.setBackground(_background);
        JScrollPane pane = new JScrollPane(_plotPane);
        pane.setBackground(_background);
        //getContentPane().add(pane, BorderLayout.CENTER);
        
        _controlPane = new NetViewerControlPane(this);
        _controlPane.setBackground(_background);
        //getContentPane().add(_controlPane, BorderLayout.SOUTH);
        
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, _plotPane, _controlPane);
        //split.setDividerLocation(0.3d);
        getContentPane().add(split, BorderLayout.CENTER);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initializeMenus();
    }
    
    /**
     * Callback:  when the state of the world changes . . .
     */
    public void stateUpdated() {
        _controlPane.stateUpdated();
        _plotPane.stateUpdated();
        //pack();
    }
    
    public void refreshView() {
        _plotPane.stateUpdated();
    }
    
    private void exitCalled() {
        _viewer.setIsClosed(true);
        setVisible(false);
        System.exit(0);
    }
    private void loadConfigCalled() {}
    private void saveConfigCalled() {}
    private void loadSnapshotCalled() {}
    private void saveSnapshotCalled() {}
    
    private void initializeMenus() {
        JMenuBar bar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem loadConfig = new JMenuItem("Load config");
        loadConfig.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { loadConfigCalled(); } });
        JMenuItem saveConfig = new JMenuItem("Save config");
        saveConfig.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { saveConfigCalled(); } });
        JMenuItem saveSnapshot = new JMenuItem("Save snapshot");
        saveSnapshot.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { saveSnapshotCalled(); } });
        JMenuItem loadSnapshot = new JMenuItem("Load snapshot");
        loadSnapshot.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { loadSnapshotCalled(); } });
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { exitCalled(); } });
        
        fileMenu.add(loadConfig);
        fileMenu.add(saveConfig);
        fileMenu.add(loadSnapshot);
        fileMenu.add(saveSnapshot);
        fileMenu.add(exit);
        bar.add(fileMenu);
        setJMenuBar(bar);
    }
}
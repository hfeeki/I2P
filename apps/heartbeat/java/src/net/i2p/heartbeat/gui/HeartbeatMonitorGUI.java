package net.i2p.heartbeat.gui;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JMenuBar;
import javax.swing.JScrollPane;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

class HeartbeatMonitorGUI extends JFrame {
    private HeartbeatMonitor _monitor;
    private HeartbeatPlotPane _plotPane;
    private HeartbeatControlPane _controlPane;
    private final static Color WHITE = new Color(255, 255, 255);
    private Color _background = WHITE;
    
    public HeartbeatMonitorGUI(HeartbeatMonitor monitor) {
        super("Heartbeat Monitor");
        _monitor = monitor;
        initializeComponents();
        pack();
        setResizable(false);
        setVisible(true);
    }
    
    HeartbeatMonitor getMonitor() { return _monitor; }
    
    /** build up all our widgets */
    private void initializeComponents() {
        getContentPane().setLayout(new BorderLayout());
        
        setBackground(_background);
        
        _plotPane = new HeartbeatPlotPane(this);
        _plotPane.setBackground(_background);
        JScrollPane pane = new JScrollPane(_plotPane);
        pane.setBackground(_background);
        getContentPane().add(pane, BorderLayout.CENTER);
        
        _controlPane = new HeartbeatControlPane(this);
        _controlPane.setBackground(_background);
        getContentPane().add(_controlPane, BorderLayout.SOUTH);
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initializeMenus();
    }
    
    public void stateUpdated() {
        _controlPane.testsUpdated();
        _plotPane.stateUpdated();
    }
    
    private void exitCalled() {
        _monitor.getState().setWasKilled(true);
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
package net.i2p.netmonitor.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JScrollPane;

import org.jfree.chart.ChartPanel;

import net.i2p.util.Log;

/**
 * Render the graph and legend
 *
 */
class JFreeChartHeartbeatPlotPane extends NetViewerPlotPane {
    private final static Log _log = new Log(JFreeChartHeartbeatPlotPane.class);
    private ChartPanel _panel;
    private JFreeChartAdapter _adapter;
        
    public JFreeChartHeartbeatPlotPane(NetViewerGUI gui) {
        super(gui);
    }
    
    public void stateUpdated() {
        if (_panel == null) {
            remove(0); // remove the dummy
            
            _adapter = new JFreeChartAdapter();
            _panel = _adapter.createPanel(_gui.getViewer());
            _panel.setBackground(_gui.getBackground());
            JScrollPane pane = new JScrollPane(_panel);
            pane.setBackground(_gui.getBackground());
            add(pane, BorderLayout.CENTER);
        } else {
            _adapter.updateChart(_panel, _gui.getViewer());
            //_gui.pack();
        }
    }
    
    protected void initializeComponents() {
        // noop
        setLayout(new BorderLayout());
        add(new JLabel(), BorderLayout.CENTER);
        //dummy.setBackground(_gui.getBackground());
        //dummy.setPreferredSize(new Dimension(800,600));
        //add(dummy);
        setPreferredSize(new Dimension(800,600));
        //add(_panel);

    }
}
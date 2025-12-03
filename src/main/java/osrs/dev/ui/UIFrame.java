package osrs.dev.ui;

import osrs.dev.dumper.Dumper;
import osrs.dev.Main;
import osrs.dev.mapping.collisionmap.CollisionMapFactory;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.tiletypemap.TileTypeMap;
import osrs.dev.mapping.tiletypemap.TileTypeMapFactory;
import osrs.dev.ui.viewport.ViewPort;
import osrs.dev.util.ImageUtil;
import osrs.dev.util.ThreadPool;
import osrs.dev.util.WorldPoint;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import static osrs.dev.ui.Components.*;

/**
 * The main UI frame for the Collision Viewer.
 */
public class UIFrame extends JFrame {
    private final JLabel mapView;
    private final JSlider zoomSlider;
    private final JSlider speedSlider;
    private final JButton upButton;
    private JTextField pathField;
    private JCheckBox downloadCacheCheckBox;
    private JComboBox<String> formatComboBox;
    private final ViewPort viewPort;
    private final WorldPoint base = new WorldPoint(3207, 3213, 0);
    private final WorldPoint center = new WorldPoint(0,0,0);
    private Future<?> current;
    private JTextField worldPointField;
    private JComboBox<ViewerMode> viewerModeComboBox;
    private ViewerMode currentViewerMode;

    /**
     * Creates a new UI frame for the Collision Viewer.
     */
    public UIFrame() {
        // Initialize viewer mode from config
        String savedMode = Main.getConfigManager().viewerMode();
        currentViewerMode = ViewerMode.fromDisplayName(savedMode);

        setIconImage(ImageUtil.loadImageResource(UIFrame.class, "icon.png"));
        setTitle("Collision Viewer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        setSize(800, 600);
        setLocationRelativeTo(null);

        viewPort = new ViewPort();

        // Main panel for image display
        JPanel imagePanel = new JPanel(new BorderLayout());
        mapView = createMapView();
        imagePanel.add(mapView, BorderLayout.CENTER);
        add(imagePanel, BorderLayout.CENTER);

        // Control panel on the right
        JPanel controlPanel = new JPanel(new BorderLayout());
        controlPanel.setPreferredSize(new Dimension(150, 300));

        // Navigation panel (buttons in plus shape)
        JPanel navigationPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        // Set up button size and add the Up button (use ^ for up)
        upButton = createDirectionButton(Direction.NORTH, e -> moveImage(Direction.NORTH));
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        navigationPanel.add(upButton, gbc);

        // Add the Left button (use < for left)
        JButton leftButton = createDirectionButton(Direction.WEST, e -> moveImage(Direction.WEST));
        gbc.gridx = 0;
        gbc.gridy = 1;
        navigationPanel.add(leftButton, gbc);

        // Add the Right button (use > for right)
        JButton rightButton = createDirectionButton(Direction.EAST, e -> moveImage(Direction.EAST));
        gbc.gridx = 2;
        gbc.gridy = 1;
        navigationPanel.add(rightButton, gbc);

        // Add the Down button (use v for down)
        JButton downButton = createDirectionButton(Direction.SOUTH, e -> moveImage(Direction.SOUTH));
        gbc.gridx = 1;
        gbc.gridy = 2;
        navigationPanel.add(downButton, gbc);

        controlPanel.add(navigationPanel, BorderLayout.CENTER);

        // Zoom slider
        zoomSlider = createZoomSlider(e -> {
            calculateBase();
            calculateCenter();
            update();
        });
        controlPanel.add(zoomSlider, BorderLayout.EAST);

        // Radio buttons for plane selection
        JPanel planeSelectionPanel = new JPanel(new GridLayout(5, 1));
        planeSelectionPanel.setBorder(BorderFactory.createTitledBorder("Select Plane"));

        ButtonGroup planeGroup = new ButtonGroup();
        JRadioButton plane1 = new JRadioButton("Plane 0", true);
        JRadioButton plane2 = new JRadioButton("Plane 1");
        JRadioButton plane3 = new JRadioButton("Plane 2");
        JRadioButton plane4 = new JRadioButton("Plane 3");

        planeGroup.add(plane1);
        planeGroup.add(plane2);
        planeGroup.add(plane3);
        planeGroup.add(plane4);

        planeSelectionPanel.add(plane1);
        planeSelectionPanel.add(plane2);
        planeSelectionPanel.add(plane3);
        planeSelectionPanel.add(plane4);

        // Action listeners for plane buttons
        ActionListener planeActionListener = e -> {
            JRadioButton source = (JRadioButton) e.getSource();
            if (source == plane1) {
                setPlane(0);
            } else if (source == plane2) {
                setPlane(1);
            } else if (source == plane3) {
                setPlane(2);
            } else if (source == plane4) {
                setPlane(3);
            }
        };

        plane1.addActionListener(planeActionListener);
        plane2.addActionListener(planeActionListener);
        plane3.addActionListener(planeActionListener);
        plane4.addActionListener(planeActionListener);

        controlPanel.add(planeSelectionPanel, BorderLayout.SOUTH);

        add(controlPanel, BorderLayout.EAST);

        // Speed slider
        speedSlider = createSpeedSlider();
        add(speedSlider, BorderLayout.NORTH);

        add(createUpdatePanel(), BorderLayout.SOUTH);

        calculateCenter();
        setupKeyBindings(imagePanel);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                upButton.requestFocusInWindow();
            }
        });
        mapView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                upButton.requestFocusInWindow();
            }
        });

        imagePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                upButton.requestFocusInWindow();
            }
        });

        addMouseWheelListener(e -> {
            if(busy())
                return;
            if (e.getWheelRotation() < 0) {
                int val = zoomSlider.getValue() - 10;
                val = val > 0 ? val : 1;
                zoomSlider.setValue(val);
            } else {
                int val = zoomSlider.getValue() + 10;
                val = val < 501 ? val : 1000;
                zoomSlider.setValue(val);
            }
        });

        createMenuBar();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                update();
            }
        });
    }

    private void createMenuBar()
    {
        // Create the menu bar
        JMenuBar menuBar = new JMenuBar();

        // Add Load button with popup menu
        JButton loadButton = new JButton("Load");
        JPopupMenu loadPopup = new JPopupMenu();
        JMenuItem loadCollisionItem = new JMenuItem("Load Collision Map...");
        JMenuItem loadTileTypeItem = new JMenuItem("Load Tile Type Map...");

        loadCollisionItem.addActionListener(e -> loadCollisionMapFromFile());
        loadTileTypeItem.addActionListener(e -> loadTileTypeMapFromFile());

        loadPopup.add(loadCollisionItem);
        loadPopup.add(loadTileTypeItem);

        loadButton.addActionListener(e -> loadPopup.show(loadButton, 0, loadButton.getHeight()));
        menuBar.add(loadButton);

        // Add Export button with popup menu
        JButton exportButton = new JButton("Export");
        JPopupMenu exportPopup = new JPopupMenu();
        JMenuItem exportCollisionItem = new JMenuItem("Export Collision Map as PNG...");
        JMenuItem exportTileTypeItem = new JMenuItem("Export Tile Type Map as PNG...");
        JMenuItem exportCombinedItem = new JMenuItem("Export Combined Map as PNG...");

        exportCollisionItem.addActionListener(e -> exportMapToPng(ViewerMode.COLLISION));
        exportTileTypeItem.addActionListener(e -> exportMapToPng(ViewerMode.TILE_TYPE));
        exportCombinedItem.addActionListener(e -> exportMapToPng(ViewerMode.COMBINED));

        exportPopup.add(exportCollisionItem);
        exportPopup.add(exportTileTypeItem);
        exportPopup.add(exportCombinedItem);

        exportButton.addActionListener(e -> exportPopup.show(exportButton, 0, exportButton.getHeight()));
        menuBar.add(exportButton);

        worldPointField = new JTextField();

        //do something when you hit enter key in text field
        worldPointField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String text = worldPointField.getText();
                String[] split = text.split(",");
                if(split.length != 3)
                    return;
                try {
                    int x = Integer.parseInt(split[0]);
                    int y = Integer.parseInt(split[1]);
                    int z = Integer.parseInt(split[2]);
                    center.setX(x);
                    center.setY(y);
                    center.setPlane(z);
                    calculateBase();
                    update();
                } catch (NumberFormatException ex) {
                    ex.printStackTrace();
                }
            }
        });

        //add a little spacing
        menuBar.add(Box.createHorizontalStrut(10));

        menuBar.add(worldPointField);

        // Add viewer mode dropdown
        menuBar.add(Box.createHorizontalStrut(10));
        menuBar.add(new JLabel("Viewer mode:"));
        menuBar.add(Box.createHorizontalStrut(5));
        viewerModeComboBox = new JComboBox<>(ViewerMode.values());
        viewerModeComboBox.setSelectedItem(currentViewerMode);
        viewerModeComboBox.setMaximumSize(new Dimension(100, 25));
        viewerModeComboBox.addActionListener(e -> {
            currentViewerMode = (ViewerMode) viewerModeComboBox.getSelectedItem();
            Main.getConfigManager().setViewerMode(currentViewerMode.getDisplayName());
            update();
        });
        menuBar.add(viewerModeComboBox);

        // Set the menu bar for the JFrame
        setJMenuBar(menuBar);
    }

    /**
     * Creates the update panel for updating the collision map.
     * @return The update panel.
     */
    private JPanel createUpdatePanel() {
        JPanel updatePanel = new JPanel(new BorderLayout());
        updatePanel.setBorder(BorderFactory.createTitledBorder("Update Dumps"));

        // Create a panel for input fields
        JPanel inputPanel = new JPanel(new GridLayout(5, 1));

        // Add a label and text field for output directory
        JLabel pathLabel = new JLabel("Output Directory:");
        pathField = new JTextField();
        pathField.setText(Main.getConfigManager().outputDir());
        inputPanel.add(pathLabel);
        inputPanel.add(pathField);

        // Add input panel to the main update panel
        updatePanel.add(inputPanel);

        downloadCacheCheckBox = new JCheckBox("Download Fresh Cache (Will download anyways if this is your first time)");
        downloadCacheCheckBox.setSelected(Main.getConfigManager().freshCache());
        downloadCacheCheckBox.addItemListener(e -> Main.getConfigManager().setFreshCache(downloadCacheCheckBox.isSelected()));
        inputPanel.add(downloadCacheCheckBox);

        // Add format selection combo box
        JLabel formatLabel = new JLabel("Serialization Format:");
        formatComboBox = new JComboBox<>(new String[]{"RoaringBitmap", "SparseBitSet"});
        formatComboBox.setSelectedItem(Main.getConfigManager().format());
        formatComboBox.addActionListener(e -> Main.getConfigManager().setFormat((String) formatComboBox.getSelectedItem()));
        inputPanel.add(formatLabel);
        inputPanel.add(formatComboBox);

        // Add the Update Collision button at the bottom
        updatePanel.add(getUpdateButton(), BorderLayout.SOUTH);
        return updatePanel;
    }

    /**
     * Requests initial focus for the up button (Purpose is to draw focus off of the sliders)
     */
    public void requestInitialFocus()
    {
        upButton.requestFocusInWindow();
    }

    /**
     * Creates the update button for updating the collision map.
     * @return The update button.
     */
    private JButton getUpdateButton() {
        JButton updateCollision = new JButton("Update Dumps");
        updateCollision.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                updateCollision.setText("Updating...");
                updateCollision.setEnabled(false);
                revalidate();
                repaint();
            });

            Main.getConfigManager().setOutputDir(pathField.getText());
            String selectedFormat = (String) formatComboBox.getSelectedItem();

            List<String> options = new ArrayList<>();
            options.add("-dir");
            options.add(pathField.getText());
            options.add("-fresh");
            options.add(downloadCacheCheckBox.isSelected() ? "y" : "n");
            options.add("-format");
            options.add(selectedFormat);

            ThreadPool.submit(() -> {
                try
                {
                    Dumper.main(options.toArray(new String[0]));
                    Main.load();
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
                SwingUtilities.invokeLater(() -> {
                    updateCollision.setText("Update Dumps");
                    updateCollision.setEnabled(true);
                    revalidate();
                    repaint();
                    update();
                });
            });

        });
        return updateCollision;
    }

    /**
     * Updates the collision map.
     */
    public void update() {
        // Check if the appropriate data is available for the current viewer mode
        if (currentViewerMode == ViewerMode.COLLISION && Main.getCollision() == null) {
            return;
        }
        if (currentViewerMode == ViewerMode.TILE_TYPE && Main.getTileTypeMap() == null) {
            return;
        }
        if (currentViewerMode == ViewerMode.COMBINED && Main.getCollision() == null && Main.getTileTypeMap() == null) {
            return;
        }

        if(busy())
            return;

        worldPointField.setText(center.getX() + "," + center.getY() + "," + center.getPlane());

        current = ThreadPool.submit(() -> {
            viewPort.render(base, mapView.getWidth(), mapView.getHeight(), zoomSlider.getValue(), currentViewerMode);
            ImageIcon imageIcon = new ImageIcon(viewPort.getCanvas());
            mapView.setIcon(imageIcon);
        });
    }

    /**
     * checks if its currently busy rendering a map frame
     * @return true if busy, false otherwise
     */
    private boolean busy()
    {
        return current != null && !current.isDone();
    }

    /**
     * Moves the image in the specified direction.
     * @param direction The direction to move the image.
     */
    private void moveImage(Direction direction) {
        if(busy())
            return;
        switch (direction)
        {
            case NORTH:
                base.north(speedSlider.getValue());
                calculateCenter();
                break;
            case SOUTH:
                base.south(speedSlider.getValue());
                calculateCenter();
                break;
            case EAST:
                base.east(speedSlider.getValue());
                calculateCenter();
                break;
            case WEST:
                base.west(speedSlider.getValue());
                calculateCenter();
                break;

        }
        update();
    }

    /**
     * Calculates the base point of the image.
     */
    private void calculateBase()
    {
        int baseX = center.getX() - zoomSlider.getValue() / 2;
        int baseY = center.getY() - zoomSlider.getValue() / 2;
        base.setX(baseX);
        base.setY(baseY);
    }

    /**
     * Calculates the center point of the image.
     */
    private void calculateCenter()
    {
        int centerX = base.getX() + zoomSlider.getValue() / 2;
        int centerY = base.getY() + zoomSlider.getValue() / 2;
        center.setX(centerX);
        center.setY(centerY);
        center.setPlane(base.getPlane());
    }

    /**
     * Sets up key bindings for the specified component.
     * @param component The component to set up key bindings for.
     */
    private void setupKeyBindings(JComponent component) {
        // Define actions for each arrow key
        Action upAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveImage(Direction.NORTH);
            }
        };

        Action downAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveImage(Direction.SOUTH);
            }
        };

        Action leftAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveImage(Direction.WEST);
            }
        };

        Action rightAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveImage(Direction.EAST);
            }
        };

        Action zeroAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPlane(0);
            }
        };

        Action oneAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPlane(1);
            }
        };

        Action twoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPlane(2);
            }
        };

        Action threeAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPlane(3);
            }
        };

        // Bind the arrow keys to actions
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("UP"), "upAction");
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("W"), "upAction");
        component.getActionMap().put("upAction", upAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("DOWN"), "downAction");
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("S"), "downAction");
        component.getActionMap().put("downAction", downAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("LEFT"), "leftAction");
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("A"), "leftAction");
        component.getActionMap().put("leftAction", leftAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("RIGHT"), "rightAction");
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("D"), "rightAction");
        component.getActionMap().put("rightAction", rightAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("0"), "zeroAction");
        component.getActionMap().put("zeroAction", zeroAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("1"), "oneAction");
        component.getActionMap().put("oneAction", oneAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("2"), "twoAction");
        component.getActionMap().put("twoAction", twoAction);

        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("3"), "threeAction");
        component.getActionMap().put("threeAction", threeAction);
    }

    /**
     * Sets the plane (floor) of the display.
     * @param plane The plane to set the display to.
     */
    private void setPlane(int plane)
    {
        if(busy())
            return;

        if(plane > 3 || plane < 0)
            return;
        SwingUtilities.invokeLater(() -> {
            base.setPlane(plane);
            center.setPlane(plane);
            update();
        });
    }

    /**
     * Opens a file chooser dialog to load a collision map from file.
     */
    private void loadCollisionMapFromFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load Collision Map");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Map files (*.dat, *.gz)", "dat", "gz"));

        // Start in the output directory if configured
        String outputDir = Main.getConfigManager().outputDir();
        if (outputDir != null && !outputDir.isEmpty()) {
            File dir = new File(outputDir);
            if (dir.exists()) {
                fileChooser.setCurrentDirectory(dir);
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                ICollisionMap loadedMap = CollisionMapFactory.load(selectedFile.getAbsolutePath());
                Main.setCollision(loadedMap);
                viewPort.invalidateCollisionCache();
                update();
                JOptionPane.showMessageDialog(this,
                        "Collision map loaded successfully from:\n" + selectedFile.getAbsolutePath(),
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Failed to load collision map:\n" + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }

    /**
     * Opens a file chooser dialog to load a tile type map from file.
     */
    private void loadTileTypeMapFromFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load Tile Type Map");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Map files (*.dat, *.gz)", "dat", "gz"));

        // Start in the output directory if configured
        String outputDir = Main.getConfigManager().outputDir();
        if (outputDir != null && !outputDir.isEmpty()) {
            File dir = new File(outputDir);
            if (dir.exists()) {
                fileChooser.setCurrentDirectory(dir);
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                TileTypeMap loadedMap = TileTypeMapFactory.load(selectedFile.getAbsolutePath());
                Main.setTileTypeMap(loadedMap);
                viewPort.invalidateTileTypeCache();
                update();
                JOptionPane.showMessageDialog(this,
                        "Tile type map loaded successfully from:\n" + selectedFile.getAbsolutePath(),
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Failed to load tile type map:\n" + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }

    /**
     * Opens a dialog to export the map as a PNG file.
     * @param mode The viewer mode to export (collision or tile type)
     */
    private void exportMapToPng(ViewerMode mode) {
        // Check if data is available
        if (mode == ViewerMode.COLLISION && Main.getCollision() == null) {
            JOptionPane.showMessageDialog(this,
                    "No collision map loaded. Please load a collision map first.",
                    "Export Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (mode == ViewerMode.TILE_TYPE && Main.getTileTypeMap() == null) {
            JOptionPane.showMessageDialog(this,
                    "No tile type map loaded. Please load a tile type map first.",
                    "Export Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (mode == ViewerMode.COMBINED && Main.getCollision() == null && Main.getTileTypeMap() == null) {
            JOptionPane.showMessageDialog(this,
                    "No maps loaded. Please load at least one map first.",
                    "Export Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ask user to select plane
        String[] planes = {"0", "1", "2", "3"};
        String selectedPlane = (String) JOptionPane.showInputDialog(
                this,
                "Select plane to export:",
                "Export " + mode.getDisplayName() + " Map",
                JOptionPane.QUESTION_MESSAGE,
                null,
                planes,
                String.valueOf(base.getPlane())
        );

        if (selectedPlane == null) {
            return; // User cancelled
        }

        int plane = Integer.parseInt(selectedPlane);

        // Show file save dialog
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export " + mode.getDisplayName() + " Map as PNG");
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG files (*.png)", "png"));

        String defaultName = mode.getDisplayName().toLowerCase().replace(" ", "_") + "_plane" + plane + ".png";
        fileChooser.setSelectedFile(new File(defaultName));

        // Start in output directory if configured
        String outputDir = Main.getConfigManager().outputDir();
        if (outputDir != null && !outputDir.isEmpty()) {
            File dir = new File(outputDir);
            if (dir.exists()) {
                fileChooser.setCurrentDirectory(dir);
            }
        }

        int result = fileChooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return; // User cancelled
        }

        File outputFile = fileChooser.getSelectedFile();
        // Ensure .png extension
        if (!outputFile.getName().toLowerCase().endsWith(".png")) {
            outputFile = new File(outputFile.getAbsolutePath() + ".png");
        }

        // Show progress dialog
        JDialog progressDialog = new JDialog(this, "Exporting...", true);
        JPanel progressPanel = new JPanel(new BorderLayout(10, 10));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JLabel progressLabel = new JLabel("Exporting " + mode.getDisplayName() + " map for plane " + plane + "...");
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressPanel.add(progressLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);

        File finalOutputFile = outputFile;
        ThreadPool.submit(() -> {
            SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));
        });

        int finalPlane = plane;
        ThreadPool.submit(() -> {
            try {
                boolean success = viewPort.exportMapToPng(mode, finalPlane, finalOutputFile);
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    if (success) {
                        JOptionPane.showMessageDialog(this,
                                "Map exported successfully to:\n" + finalOutputFile.getAbsolutePath(),
                                "Export Complete",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this,
                                "Failed to export map.",
                                "Export Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    JOptionPane.showMessageDialog(this,
                            "Failed to export map:\n" + ex.getMessage(),
                            "Export Error",
                            JOptionPane.ERROR_MESSAGE);
                });
                ex.printStackTrace();
            }
        });
    }
}
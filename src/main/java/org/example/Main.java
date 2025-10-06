package org.example;

import characters.Horse;
import characters.Human;
import characters.Team;
import characters.Unit;
import world.World;
import world.WorldPanel;

import javax.swing.*;

// Press Shift twice to open the Search Everywhere dialog and type `show whitespaces`,
// then press Enter. You can now see whitespace characters in your code.
import java.awt.*;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            World world = new World(100, 160, 2); // bigger map to test camera

            var horse = world.spawnActor(new Horse(), 6, 6);
            horse.setTeam(Team.RED);

            Human archer = Human.basicFootman();
            archer.setName("Archer");
            archer.setHasShortBow(true);
//            world.spawnActor(archer, 6, 8).setTeam(Team.BLUE);

            Human redFoot = Human.basicFootman();
            redFoot.setName("Red Footman");
            world.spawnActor(redFoot, 3, 3).setTeam(Team.RED);

            Human blueFoot = Human.basicFootman();
            blueFoot.setName("Blue Footman");
            world.spawnActor(blueFoot, 7, 5).setTeam(Team.BLUE);

            Human builder = Human.basicFootman();
            builder.setName("Builder");
            builder.setCanBuildWalls(true);
            world.spawnActor(builder, 4, 5).setTeam(Team.RED);

            // Control point
            world.addControlPoint(new world.ControlPoint(1, 5, 8, 5, 1.5));

            // Frame
            JFrame frame = new JFrame("World Grid");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setLayout(new BorderLayout());

            // Top bar (status + build)
            JLabel status = new JLabel("—");
            JButton buildBtn = new JButton("Build");
            buildBtn.setEnabled(false); // WorldPanel will enable when a builder is selected

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            topBar.add(status);
            topBar.add(buildBtn);

            // World panel (camera viewport) — NO JScrollPane
            int viewportW = 1200;
            int viewportH = 800;
            WorldPanel panel = new WorldPanel(world, status, buildBtn, viewportW, viewportH);
            panel.setLayer(World.LAYER_UNIT);

            // Zoom
            JSlider zoom = new JSlider(16, 80, 28);
            zoom.addChangeListener(e -> panel.setCellSize(zoom.getValue()));

            // Layout
            frame.add(topBar, BorderLayout.NORTH);
            frame.add(panel,   BorderLayout.CENTER);  // <-- no scroll pane
            frame.add(zoom,    BorderLayout.SOUTH);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // make sure key bindings (ESC) work immediately
            panel.requestFocusInWindow();
        });
    }

}
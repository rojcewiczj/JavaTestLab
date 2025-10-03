package org.example;

import characters.Human;
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
            World world = new World(10, 16, 2);

            // Create a Human Actor and spawn it as a Unit at (2,3)
            Human footman = Human.basicFootman();
            Unit soldier = world.spawnActor(footman, 2, 3);
            Unit soldier2 = world.spawnActor(footman, 2, 4);
            Unit soldier3  = world.spawnActor(footman, 2, 6);
            Unit soldier4  = world.spawnActor(footman, 2, 7);
            Unit soldier5  = world.spawnActor(footman, 2, 8);
            // UI
            JFrame frame = new JFrame("World Grid");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            JLabel status = new JLabel("—");
            WorldPanel panel = new WorldPanel(world, status);
            panel.setLayer(World.LAYER_UNIT);

            JSlider zoom = new JSlider(16, 64, 28);
            zoom.addChangeListener(e -> panel.setCellSize(zoom.getValue()));

            frame.add(new JScrollPane(panel), BorderLayout.CENTER);
            frame.add(zoom, BorderLayout.SOUTH);
            frame.add(status, BorderLayout.NORTH);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
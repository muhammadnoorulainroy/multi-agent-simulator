package fr.emse;

import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;

public class BasicSimulator {

    public static void main(String[] args) throws Exception {
        IniFile ifile = new IniFile("configuration.ini");
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();
        
        System.out.println("Simulation Parameters");
        System.out.println("Display:    " + sp.display);
        System.out.println("Simulation: " + sp.simulation);
        System.out.println("MQTT:       " + sp.mqtt);
        System.out.println("Robots:     " + sp.nbrobot);
        System.out.println("Obstacles:  " + sp.nbobstacle);
        System.out.println("Seed:       " + sp.seed);
        System.out.println("Field:      " + sp.field);
        System.out.println("Debug:      " + sp.debug);
        System.out.println("Wait Time:  " + sp.waittime);
        System.out.println("Steps:      " + sp.step);

        System.out.println("\nEnvironment Parameters");
        System.out.println("Rows:       " + sp.rows);
        System.out.println("Columns:    " + sp.columns);

        System.out.println("\nDisplay Parameters");
        System.out.println("X:          " + sp.display_x);
        System.out.println("Y:          " + sp.display_y);
        System.out.println("Width:      " + sp.display_width);
        System.out.println("Height:     " + sp.display_height);
        System.out.println("Title:      " + sp.display_title);

        System.out.println("\nColor Parameters");
        System.out.println("Robot:      " + sp.colorrobot);
        System.out.println("Goal:       " + sp.colorgoal);
        System.out.println("Obstacle:   " + sp.colorobstacle);
        System.out.println("Other:      " + sp.colorother);
        System.out.println("Unknown:    " + sp.colorunknown);
    }
}

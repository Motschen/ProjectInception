package ai.arcblroth.projectInception.client.mc;

import ai.arcblroth.projectInception.client.AbstractGameInstance;
import ai.arcblroth.projectInception.ProjectInception;
import ai.arcblroth.projectInception.ProjectInceptionEarlyRiser;
import ai.arcblroth.projectInception.block.GameBlockEntity;
import ai.arcblroth.projectInception.block.GameMultiblock;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MinecraftGameInstance extends AbstractGameInstance<GameBlockEntity> {

    private Process process;
    private final Object processLock = new Object();
    private boolean isProcessBeingKilled = false;
    private final ArrayList<String> commandLine;

    public MinecraftGameInstance(GameMultiblock<GameBlockEntity> multiblock) {
        super(multiblock);
        this.commandLine = buildCommandLine();
    }

    private ArrayList<String> buildCommandLine() {
        ArrayList<String> commandLine = ProjectInceptionEarlyRiser.newCommandLineForForking();
        String newInstancePrefix = ProjectInceptionEarlyRiser.INSTANCE_PREFIX + "-" + instanceNumber;
        commandLine.add("-D" + ProjectInceptionEarlyRiser.ARG_IS_INNER + "=true");
        commandLine.add("-D" + ProjectInceptionEarlyRiser.ARG_DISPLAY_WIDTH
                + "=" + (multiblock.sizeX * ProjectInceptionEarlyRiser.DISPLAY_SCALE));
        commandLine.add("-D" + ProjectInceptionEarlyRiser.ARG_DISPLAY_HEIGHT
                + "=" + (multiblock.sizeY * ProjectInceptionEarlyRiser.DISPLAY_SCALE));
        commandLine.add("-D" + ProjectInceptionEarlyRiser.ARG_INSTANCE_PREFIX + "=" + newInstancePrefix);
        if(!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            commandLine.add(ProjectInception.MAIN_CLASS);
            List<String> cmdArgs = Arrays.asList(ProjectInception.ARGUMENTS);
            commandLine.addAll(cmdArgs);
            if(!cmdArgs.contains("--disableMultiplayer")) {
                commandLine.add("--disableMultiplayer");
            }
        } else {
            commandLine.add(ProjectInception.DEV_MAIN_CLASS);
            commandLine.add("--disableMultiplayer");
        }
        return commandLine;
    }

    @Override
    protected String getNewInstanceQueueDirectory() {
        return ProjectInceptionEarlyRiser.INSTANCE_PREFIX + "-" + instanceNumber;
    }

    @Override
    public void start() {
        if(process == null || !process.isAlive()) {
            try {
                ProjectInception.LOGGER.log(Level.INFO, "Running command line: " + String.join(" ", commandLine));
                process = new ProcessBuilder(commandLine).inheritIO().start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        super.start();
    }

    @Override
    protected void stopInner() {
        if(this.process != null && this.process.isAlive()) {
            synchronized (processLock) {
                isProcessBeingKilled = true;
            }
            this.process.destroy();
            if(this.process.isAlive()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
                if (this.process.isAlive()) {
                    this.process.destroyForcibly();
                }
            }
            this.process = null;
            synchronized (processLock) {
                isProcessBeingKilled = false;
            }
        }
    }

    @Override
    public boolean isAlive() {
        synchronized (processLock) {
            return !isProcessBeingKilled && process != null && process.isAlive();
        }
    }

}
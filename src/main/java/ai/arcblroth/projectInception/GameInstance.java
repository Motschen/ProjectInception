package ai.arcblroth.projectInception;

import ai.arcblroth.projectInception.block.GameMultiblock;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.TailerDirection;
import net.openhft.chronicle.wire.DocumentContext;
import org.apache.logging.log4j.Level;
import org.lwjgl.BufferUtils;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.arcblroth.projectInception.QueueProtocol.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.memAddress;

public class GameInstance {

    private static int instanceCounter = 0;
    private static final Object cleanUpLock = new Object();
    private static final ArrayList<GameInstance> instancesToCleanUp = new ArrayList<>();
    private final int instanceNumber;

    private final GameMultiblock multiblock;

    private Process process;
    private final Object processLock = new Object();
    private boolean isProcessBeingKilled = false;
    private final ArrayList<String> commandLine;

    private final ChronicleQueue childQueue;
    private final ExcerptTailer tailer;
    private OptionalLong tailerStartIndex = OptionalLong.empty();
    private final Thread tailerThread;

    private int lastWidth = 0;
    private int lastHeight = 0;
    private double lastMouseX = 0.5;
    private double lastMouseY = 0.5;
    private boolean showCursor = true;

    private ByteBuffer texture = null;
    private Identifier textureId = null;
    private NativeImageBackedTexture lastTextureImage = null;

    private final Object send2ChildLock = new Object();
    private ArrayList<Message> messages2ChildToSend = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ProjectInception.LOGGER.log(Level.INFO, "Destroying game instances on exit...");
            stopAllGameInstances();
            if(ProjectInception.toParentQueue != null
            && !ProjectInception.toParentQueue.isClosed()) {
                ProjectInception.toParentQueue.close();
            }
            iSleep(1000);
            File queueDir = new File(MinecraftClient.getInstance().runDirectory, "projectInception");
            ProjectInceptionEarlyRiser.yeetChronicleQueues(queueDir, false);
        }));
    }

    public GameInstance(GameMultiblock multiblock) {
        instanceNumber = instanceCounter;
        instanceCounter++;
        synchronized (cleanUpLock) {
            instancesToCleanUp.add(this);
        }

        this.multiblock = multiblock;

        commandLine = new ArrayList<>();
        commandLine.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        commandLine.addAll(jvmArgs);
        if(!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            if (!jvmArgs.stream().anyMatch(s -> s.contains("-cp") || s.contains("-classpath"))) {
                commandLine.add("-cp");
                commandLine.add(System.getProperty("java.class.path"));
            }
            if (!jvmArgs.stream().anyMatch(s -> s.contains("-Djava.library.path"))) {
                commandLine.add("-Djava.library.path=" + System.getProperty("java.library.path"));
            }
        } else {
            commandLine.removeIf(s -> s.startsWith("-javaagent") || s.startsWith("-agentlib"));
            commandLine.add("-cp");
            commandLine.add(System.getProperty("java.class.path"));
            if(System.getProperty("java.library.path").length() > 0) {
                commandLine.add("-Djava.library.path=" + System.getProperty("java.library.path"));
            }
        }
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

        this.childQueue = ProjectInceptionEarlyRiser.buildQueue(
                new File(MinecraftClient.getInstance().runDirectory, "projectInception" + File.separator + newInstancePrefix)
        );
        this.tailer = this.childQueue.createTailer("projectInceptionGameInstance").direction(TailerDirection.NONE);
        this.tailerThread = new Thread(this::tailerThread);
    }

    public void start() {
        if(process == null || !process.isAlive()) {
            try {
                ProjectInception.LOGGER.log(Level.INFO, "Running command line: " + String.join(" ", commandLine));
                process = new ProcessBuilder(commandLine).inheritIO().start();
                this.tailerThread.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void stop(boolean async) {
        Runnable stopFunc = () -> {
            if(this.process != null && this.process.isAlive()) {
                synchronized (processLock) {
                    isProcessBeingKilled = true;
                }
                ProjectInception.LOGGER.log(Level.DEBUG, "Destroying game instance #" + this.instanceNumber);
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

            if(this.childQueue != null && !this.childQueue.isClosed()) {
                this.childQueue.close();
            }
        };
        if(async) {
            new Thread(stopFunc).start();
        } else {
            stopFunc.run();
        }
    }

    private void tailerThread() {
        Thread.currentThread().setName("Game Instance Tailer " + instanceNumber);
        final AtomicBoolean isTextureUploading = new AtomicBoolean(false);
        final Object textureUploadLock = new Object();
        final ExcerptAppender appender = this.childQueue.acquireAppender();
        try {
            while (process != null && process.isAlive()) {
                synchronized (send2ChildLock) {
                    if (messages2ChildToSend.size() > 0) {
                        messages2ChildToSend.forEach((message) -> writeParent2ChildMessage(message, appender));
                        messages2ChildToSend.clear();
                    }
                }
                this.texture = getLastTexture();
                if (this.textureId == null) {
                    if (this.texture != null) {
                        isTextureUploading.set(true);
                        RenderSystem.recordRenderCall(() -> {
                            synchronized (textureUploadLock) {
                                NativeImage image = new NativeImage(NativeImage.Format.ABGR, lastWidth, lastHeight, false, memAddress(this.texture));
                                this.lastTextureImage = new NativeImageBackedTexture(image);
                                this.textureId = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("project_inception_game", lastTextureImage);
                                isTextureUploading.set(false);
                                textureUploadLock.notify();
                            }
                        });
                    }
                } else {
                    if (this.lastTextureImage != null) {
                        isTextureUploading.set(true);
                        RenderSystem.recordRenderCall(() -> {
                            synchronized (textureUploadLock) {
                                try {
                                    this.lastTextureImage.bindTexture();
                                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                                    this.lastTextureImage.upload();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                isTextureUploading.set(false);
                                textureUploadLock.notify();
                            }
                        });
                    }
                }
                if(isTextureUploading.get()) {
                    synchronized (textureUploadLock) {
                        textureUploadLock.wait();
                    }
                } else {
                    int framerateLimit = MinecraftClient.getInstance().getWindow().getFramerateLimit();
                    if (framerateLimit != 0) {
                        iSleep(1000 / framerateLimit);
                    } else {
                        iSleep(1);
                    }
                }
            }
        } catch (InterruptedException ignored) {

        } finally {
            System.out.println("closing texture 1");
            RenderSystem.recordRenderCall(() -> {
                System.out.println("closing texture 2");
                if(this.lastTextureImage != null) {
                    // this segfaults Minecraft and I don't know why
                    // this.lastTextureImage.close();
                    this.lastTextureImage = null;
                }
                this.texture = null;
                this.textureId = null;
            });
        }
    }

    private ByteBuffer getLastTexture() {
        synchronized (processLock) {
            if(isProcessBeingKilled) return null;
            if (process == null || !process.isAlive()) return null;
            this.tailer.toEnd();
            if (this.tailer.index() == 0) return texture;
            if(!tailerStartIndex.isPresent()) {
                tailerStartIndex = OptionalLong.of(this.tailer.toStart().index());
                this.tailer.toEnd();
            }
            byte tries = 0; // Prevent softlocking in case the child instance is lagging
            while(!QueueProtocol.peekMessageType(this.tailer).equals(QueueProtocol.MessageType.IMAGE)) {
                tries++;
                if(this.tailer.index() - 1 == tailerStartIndex.getAsLong() || tries > 8) return texture;
                this.tailer.moveToIndex(this.tailer.index() - 1);
            }
            try (DocumentContext dc = this.tailer.readingDocument()) {
                if (dc.isPresent()) {
                    Bytes<?> bytes = dc.wire().bytes();
                    if(bytes.readByte() != QueueProtocol.MessageType.IMAGE.header) throw new IllegalStateException();
                    lastWidth = bytes.readInt();
                    lastHeight = bytes.readInt();
                    showCursor = bytes.readBoolean();
                    if (texture != null) {
                        texture.rewind();
                    }
                    if (texture == null || texture.capacity() < lastWidth * lastHeight * 4) {
                        texture = BufferUtils.createByteBuffer(lastWidth * lastHeight * 4);
                    }
                    bytes.read(texture);
                    texture.rewind();
                    for (int i = 0; i < texture.capacity(); i += 4) {
                        // on the sending side alpha is zero, so
                        // we make sure it gets set to 100% here
                        texture.put(i + 3, (byte) 255);
                    }
                }
                return texture;
            }
        }
    }

    private static void iSleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Identifier getLastTextureId() {
        return textureId;
    }

    public void click(double hitX, double hitY) {
        MouseSetPosMessage message1 = new MouseSetPosMessage();
        message1.x = hitX;
        message1.y = hitY;
        lastMouseX = hitX;
        lastMouseY = hitY;
        sendParent2ChildMessage(message1);
        MouseButtonMessage message2 = new MouseButtonMessage();
        message2.button = GLFW_MOUSE_BUTTON_LEFT;
        message2.message = GLFW_PRESS;
        sendParent2ChildMessage(message2);
        message2.message = GLFW_RELEASE;
        sendParent2ChildMessage(message2);
    }

    public void sendParent2ChildMessage(Message message) {
        if(message instanceof MouseMoveMessage) {
            MouseMoveMessage mmMessage = (MouseMoveMessage) message;
            if(this.shouldShowCursor()) {
                mmMessage.x = Math.min(Math.max(mmMessage.x, 0), 1);
                mmMessage.y = Math.min(Math.max(mmMessage.y, 0), 1);
            }
            lastMouseX = mmMessage.x;
            lastMouseY = mmMessage.y;
        } else if(message instanceof MouseSetPosMessage) {
            MouseSetPosMessage mpMessage = (MouseSetPosMessage) message;
            if(this.shouldShowCursor()) {
                mpMessage.x = Math.min(Math.max(mpMessage.x, 0), 1);
                mpMessage.y = Math.min(Math.max(mpMessage.y, 0), 1);
            }
            lastMouseX = mpMessage.x;
            lastMouseY = mpMessage.y;
        }
        synchronized (send2ChildLock) {
            this.messages2ChildToSend.add(message);
        }
    }

    public static void stopAllGameInstances() {
        synchronized (cleanUpLock) {
            instancesToCleanUp.forEach(g -> {
                g.stop(false);
            });
            instancesToCleanUp.clear();
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public double getLastMouseX() {
        return lastMouseX;
    }

    public double getLastMouseY() {
        return lastMouseY;
    }

    public boolean shouldShowCursor() {
        return showCursor;
    }

}

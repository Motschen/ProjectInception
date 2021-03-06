package ai.arcblroth.projectInception.block;

import ai.arcblroth.projectInception.ProjectInceptionClient;
import ai.arcblroth.projectInception.client.InceptionInterfaceScreen;
import ai.arcblroth.projectInception.client.taterwebz.TaterwebzControlScreen;
import ai.arcblroth.projectInception.item.InceptionInterfaceItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class TaterwebzBlock extends AbstractDisplayBlock<TaterwebzBlockEntity> {

    public TaterwebzBlock(Settings settings) {
        super(settings, TaterwebzBlockEntity.class);
    }

    @Override
    public TaterwebzBlockEntity createDisplayBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new TaterwebzBlockEntity(blockPos, blockState);
    }

    @Override
    public void click(TaterwebzBlockEntity te, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, double hitX, double hitY) {
        if (world.isClient) {
            clickClient(te, state, world, pos, player, hand, hitX, hitY);
        }
    }

    @Environment(EnvType.CLIENT)
    public void clickClient(TaterwebzBlockEntity te, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, double hitX, double hitY) {
        if (player.getStackInHand(hand).getItem() instanceof InceptionInterfaceItem) {
            AbstractDisplayBlockEntity be = (AbstractDisplayBlockEntity) world.getBlockEntity(te.controllerBlockPos);
            MinecraftClient.getInstance().openScreen(new InceptionInterfaceScreen(be));
            player.sendMessage(new TranslatableText("message.project_inception.escape", ProjectInceptionClient.EXIT_INNER_LOCK.getBoundKeyLocalizedText()), true);
        } else {
            if(player.isSneaking() && te.getControllerBlockPos() != null) {
                BlockEntity beController = world.getBlockEntity(te.getControllerBlockPos());
                if(beController instanceof TaterwebzBlockEntity) {
                    MinecraftClient.getInstance().openScreen(new TaterwebzControlScreen((TaterwebzBlockEntity) beController));
                }
            } else {
                AbstractDisplayBlockEntity be = (AbstractDisplayBlockEntity) world.getBlockEntity(te.controllerBlockPos);
                be.getGameInstance().click(hitX, hitY);
            }
        }
    }

}

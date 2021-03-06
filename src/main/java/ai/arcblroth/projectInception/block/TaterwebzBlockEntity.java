package ai.arcblroth.projectInception.block;

import ai.arcblroth.projectInception.ProjectInception;
import ai.arcblroth.projectInception.client.taterwebz.TaterwebzInstance;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

public class TaterwebzBlockEntity extends AbstractDisplayBlockEntity<TaterwebzBlockEntity> {

    private TaterwebzInstance instance = null;

    public TaterwebzBlockEntity(BlockPos pos, BlockState state) {
        super(ProjectInception.TATERWEBZ_BLOCK_ENTITY_TYPE, TaterwebzBlockEntity.class, pos, state);
    }

    @Override
    public void turnOff() {
        if(this.world != null && this.world.isClient && this.isController && instance != null) {
            instance.stop(true);
        }
        super.turnOff();
    }

    @Override
    public void tick() {
        super.tick();
        if(isOn && !this.isController) {
            BlockEntity blockEntity = world.getBlockEntity(controllerBlockPos);
            if(!(blockEntity instanceof TaterwebzBlockEntity)) {
                instance = null;
            } else if (!((TaterwebzBlockEntity) blockEntity).isOn) {
                instance = null;
            } else if(world.isClient) {
                this.instance = ((TaterwebzBlockEntity) blockEntity).instance;
            }
        }
    }

    @Override
    public void setController(boolean controller) {
        super.setController(controller);
        if(this.world != null && this.world.isClient && this.multiblock != null) {
            instance = new TaterwebzInstance(this.multiblock);
            instance.start();
        }
    }

    @Override
    public TaterwebzInstance getGameInstance() {
        return instance;
    }

}

package dev.leadlight.mixin.sodium;

import dev.leadlight.sodium.TerrainVertex;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the block id on the vertex itself, and carries it through the copy the translucent sorter
 * makes. {@link TerrainVertex} says why it cannot ride on the material.
 * <p>
 * The copy is the whole reason this mixin exists rather than a bare field.
 * {@code FullTQuad.initVertices} does not keep the vertices it is handed, it copies them one field
 * at a time through {@code copyVertexTo} into an array of its own, so a field this class adds and
 * that method does not know about is dropped in silence. The sorter then writes those copies out,
 * and every translucent quad reaches the encoder with an id of nought.
 * <p>
 * Source first, destination second: {@code copyVertexTo(from, to)}, read off the caller in
 * {@code FullTQuad.initVertices}, which loads the argument array's element before its own.
 */
@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
public abstract class ChunkVertexMixin implements TerrainVertex {

	@Unique
	private int leadlight$blockId;

	@Unique
	private int leadlight$blockOrigin;

	@Override
	public int leadlightBlockId() {
		return this.leadlight$blockId;
	}

	@Override
	public void leadlightBlockId(int id) {
		this.leadlight$blockId = id;
	}

	@Override
	public int leadlightBlockOrigin() {
		return this.leadlight$blockOrigin;
	}

	@Override
	public void leadlightBlockOrigin(int packed) {
		this.leadlight$blockOrigin = packed;
	}

	@Inject(method = "copyVertexTo", at = @At("TAIL"), require = 1)
	private static void leadlight$copyId(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex to,
			CallbackInfo callback) {
		((TerrainVertex) to).leadlightBlockId(((TerrainVertex) from).leadlightBlockId());
		((TerrainVertex) to).leadlightBlockOrigin(((TerrainVertex) from).leadlightBlockOrigin());
	}
}

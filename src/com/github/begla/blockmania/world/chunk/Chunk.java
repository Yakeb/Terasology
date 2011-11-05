/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world.chunk;

import com.github.begla.blockmania.blocks.Block;
import com.github.begla.blockmania.blocks.BlockManager;
import com.github.begla.blockmania.datastructures.AABB;
import com.github.begla.blockmania.datastructures.BlockmaniaArray;
import com.github.begla.blockmania.datastructures.BlockmaniaSmartArray;
import com.github.begla.blockmania.main.Blockmania;
import com.github.begla.blockmania.main.BlockmaniaConfiguration;
import com.github.begla.blockmania.utilities.Helper;
import com.github.begla.blockmania.world.LocalWorldProvider;
import com.github.begla.blockmania.world.entity.StaticEntity;
import javolution.util.FastList;
import org.lwjgl.util.vector.Vector3f;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.logging.Level;

/**
 * Chunks are the basic components of the world. Each chunk contains a fixed amount of blocks
 * determined by its dimensions. Chunks are used to manage the world efficiently and
 * to reduce the batch count within the render loop.
 * <p/>
 * Chunks are tessellated on creation and saved to vertex arrays. From those VBOs are generated
 * which are then used for the actual rendering process.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public class Chunk extends StaticEntity implements Comparable<Chunk>, Externalizable {

    protected static final
    Vector3f[] _lightDirections = {new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
    /* ------ */
    protected boolean _dirty, _lightDirty, _fresh;
    /* ------ */
    protected LocalWorldProvider _parent;
    /* ------ */
    protected final BlockmaniaArray _blocks;
    protected final BlockmaniaSmartArray _sunlight, _light;
    /* ------ */
    protected AABB _aabb;
    /* ------ */
    private ChunkMesh _activeMesh;
    private ChunkMesh _newMesh;
    /* ------ */
    private final ChunkMeshGenerator _meshGenerator;
    /* ------ */
    private boolean _visible = false;
    /* ------ */
    private boolean _disposed = false;

    public enum LIGHT_TYPE {
        BLOCK,
        SUN
    }

    public Chunk() {
        _meshGenerator = new ChunkMeshGenerator(this);

        _blocks = new BlockmaniaArray(getChunkDimensionX(), (int) getChunkDimensionY(), (int) getChunkDimensionZ());
        _sunlight = new BlockmaniaSmartArray(getChunkDimensionX(), (int) getChunkDimensionY(), (int) getChunkDimensionZ());
        _light = new BlockmaniaSmartArray(getChunkDimensionX(), (int) getChunkDimensionY(), (int) getChunkDimensionZ());

        _lightDirty = true;
        _dirty = true;
        _fresh = true;
    }

    public static int getChunkDimensionX() {
        return (Integer) BlockmaniaConfiguration.getInstance().getConfig().get("Chunk.dimensionX");
    }

    public static int getChunkDimensionY() {
        return (Integer) BlockmaniaConfiguration.getInstance().getConfig().get("Chunk.dimensionY");
    }

    public static int getChunkDimensionZ() {
        return (Integer) BlockmaniaConfiguration.getInstance().getConfig().get("Chunk.dimensionZ");
    }

    /**
     * Init. the chunk with a parent world and an absolute position.
     *
     * @param p        The parent world
     * @param position The absolute position of the chunk within the world
     */
    public Chunk(LocalWorldProvider p, Vector3f position) {
        this();

        setPosition(position);
        _parent = p;
    }

    /**
     * Tries to load a chunk from disk. If the chunk is not present,
     * it is created from scratch.
     *
     * @return True if a generation has been executed
     */
    public boolean generate() {
        if (_fresh) {
            // Apply all generators to this chunk
            long timeStart = System.currentTimeMillis();

            _parent.getChunkGenerator("terrain").generate(this);
            _parent.getChunkGenerator("resources").generate(this);
            _parent.getChunkGenerator("forest").generate(this);

            generateSunlight();
            _fresh = false;

            Blockmania.getInstance().getLogger().log(Level.FINEST, "Chunk ({1}) generated ({0}s).", new Object[]{(System.currentTimeMillis() - timeStart) / 1000d, this});
            return true;
        }
        return false;
    }

    /**
     * Updates the light of this chunk.
     */
    public void updateLight() {
        if (!_fresh) { // Do NOT update fresh chunks
            for (int x = 0; x < getChunkDimensionX(); x++) {
                for (int z = 0; z < (int) getChunkDimensionZ(); z++) {
                    for (int y = 0; y < (int) getChunkDimensionY(); y++) {
                        byte lightValue = getLight(x, y, z, LIGHT_TYPE.SUN);

                        // Spread the sunlight in translucent blocks with a light value greater than zero.
                        if (lightValue > 0 && BlockManager.getInstance().getBlock(getBlock(x, y, z)).isTranslucent()) {
                            spreadLight(x, y, z, lightValue, LIGHT_TYPE.SUN);
                        }
                    }
                }
            }
            setLightDirty(false);
        }
    }

    /**
     * Generates the initial sunlight.
     */
    private void generateSunlight() {
        for (int x = 0; x < getChunkDimensionX(); x++) {
            for (int z = 0; z < (int) getChunkDimensionZ(); z++) {
                refreshSunlightAtLocalPos(x, z, false, false);
            }
        }
    }

    /**
     * Calculates the sunlight at a given column within the chunk.
     *
     * @param x               Local block position on the x-axis
     * @param z               Local block position on the z-axis
     * @param spreadLight     Spread light if a light value is greater than the old one
     * @param refreshSunlight Refreshes the sunlight using the surrounding chunks when the light value is lower than before
     */
    public void refreshSunlightAtLocalPos(int x, int z, boolean spreadLight, boolean refreshSunlight) {
        boolean covered = false;

        for (int y = (int) getChunkDimensionY() - 1; y >= 0; y--) {
            byte blockId = _blocks.get(x, y, z);
            Block b = BlockManager.getInstance().getBlock(blockId);

            // Remember if this "column" is covered
            if ((!b.isBlockInvisible() && b.getBlockForm() != Block.BLOCK_FORM.BILLBOARD) && !covered) {
                covered = true;
            }

            byte oldValue = _sunlight.get(x, y, z);
            byte newValue;

            // If the column is not covered...
            if (!covered) {
                if (b.isBlockInvisible() || b.getBlockForm() == Block.BLOCK_FORM.BILLBOARD)
                    _sunlight.set(x, y, z, (byte) 15);
                else
                    _sunlight.set(x, y, z, (byte) 0x0);

                newValue = _sunlight.get(x, y, z);

                // Otherwise the column is covered. Don't generate any light in the cells...
            } else {
                _sunlight.set(x, y, z, (byte) 0);

                // Update the sunlight at the current position (check the surrounding cells)
                if (refreshSunlight) {
                    refreshLightAtLocalPos(x, y, z, LIGHT_TYPE.SUN);
                }

                newValue = _sunlight.get(x, y, z);
            }


            if (spreadLight && oldValue > newValue)
                unspreadLight(x, y, z, oldValue, Chunk.LIGHT_TYPE.SUN);
            else if (spreadLight && oldValue < newValue) {
                /*
                * Spread sunlight if the new light value is more intense
                * than the old value.
                */
                spreadLight(x, y, z, newValue, LIGHT_TYPE.SUN);
            }
        }
    }

    /**
     * @param x    Local block position on the x-axis
     * @param y    Local block position on the y-axis
     * @param z    Local block position on the z-axis
     * @param type The type of the light
     */
    public void refreshLightAtLocalPos(int x, int y, int z, LIGHT_TYPE type) {
        int blockPosX = getBlockWorldPosX(x);
        int blockPosZ = getBlockWorldPosZ(z);

        byte bType = getBlock(x, y, z);

        // If a block was just placed, remove the light value at this point
        if (!BlockManager.getInstance().getBlock(bType).isTranslucent()) {
            setLight(x, y, z, (byte) 0, type);
        } else {
            // If the block was removed: Find the brightest neighbor and
            // set the current light value to this value - 1
            byte val = getParent().getLight(blockPosX, y, blockPosZ, type);
            byte val1 = getParent().getLight(blockPosX + 1, y, blockPosZ, type);
            byte val2 = getParent().getLight(blockPosX - 1, y, blockPosZ, type);
            byte val3 = getParent().getLight(blockPosX, y, blockPosZ + 1, type);
            byte val4 = getParent().getLight(blockPosX, y, blockPosZ - 1, type);
            byte val5 = getParent().getLight(blockPosX, y + 1, blockPosZ, type);
            byte val6 = getParent().getLight(blockPosX, y - 1, blockPosZ, type);

            byte max = (byte) (Math.max(Math.max(Math.max(val1, val2), Math.max(val3, val4)), Math.max(val5, val6)) - 1);

            if (max < 0) {
                max = 0;
            }

            // Do nothing if the current light value is brighter
            byte res = (byte) Math.max(max, val);

            // Finally set the new light value
            setLight(x, y, z, res, type);
        }
    }

    /**
     * Recursive light calculation.
     *
     * @param x          Local block position on the x-axis
     * @param y          Local block position on the y-axis
     * @param z          Local block position on the z-axis
     * @param lightValue The light value used to spread the light
     * @param type       The type of the light
     */
    public void unspreadLight(int x, int y, int z, byte lightValue, LIGHT_TYPE type) {
        FastList<Vector3f> brightSpots = new FastList<Vector3f>();
        unspreadLight(x, y, z, lightValue, 0, type, brightSpots);

        for (Vector3f pos : brightSpots) {
            getParent().spreadLight((int) pos.x, (int) pos.y, (int) pos.z, _parent.getLight((int) pos.x, (int) pos.y, (int) pos.z, type), 0, type);
        }
    }

    /**
     * Recursive light calculation.
     *
     * @param x           Local block position on the x-axis
     * @param y           Local block position on the y-axis
     * @param z           Local block position on the z-axis
     * @param lightValue  The light value used to spread the light
     * @param depth       Depth of the recursion
     * @param type        The type of the light
     * @param brightSpots Log of light spots, which where brighter than the current light node
     */
    public void unspreadLight(int x, int y, int z, byte lightValue, int depth, LIGHT_TYPE type, FastList<Vector3f> brightSpots) {
        int blockPosX = getBlockWorldPosX(x);
        int blockPosZ = getBlockWorldPosZ(z);

        // Remove the light at this point
        getParent().setLight(blockPosX, y, blockPosZ, (byte) 0x0, type);

        for (int i = 0; i < 6; i++) {

            byte neighborValue = getParent().getLight(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z, type);
            byte neighborType = getParent().getBlock(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z);

            if (neighborValue < lightValue && neighborValue > 0 && BlockManager.getInstance().getBlock(neighborType).isTranslucent()) {
                getParent().unspreadLight(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z, (byte) (lightValue - 1), depth + 1, type, brightSpots);
            } else if (neighborValue >= lightValue) {
                brightSpots.add(new Vector3f(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z));
            }
        }
    }


    /**
     * Recursive light calculation.
     *
     * @param x          Local block position on the x-axis
     * @param y          Local block position on the y-axis
     * @param z          Local block position on the z-axis
     * @param lightValue The light value used to spread the light
     * @param type       The type of the light
     */
    public void spreadLight(int x, int y, int z, byte lightValue, LIGHT_TYPE type) {
        spreadLight(x, y, z, lightValue, 0, type);
    }

    /**
     * Recursive light calculation.
     *
     * @param x          Local block position on the x-axis
     * @param y          Local block position on the y-axis
     * @param z          Local block position on the z-axis
     * @param lightValue The light value used to spread the light
     * @param depth      Depth of the recursion
     * @param type       The type of the light
     */
    public void spreadLight(int x, int y, int z, byte lightValue, int depth, LIGHT_TYPE type) {
        if (depth > lightValue || lightValue - depth < 1) {
            return;
        }

        int blockPosX = getBlockWorldPosX(x);
        int blockPosZ = getBlockWorldPosZ(z);

        byte newLightValue;
        newLightValue = (byte) (lightValue - depth);

        getParent().setLight(blockPosX, y, blockPosZ, newLightValue, type);

        for (int i = 0; i < 6; i++) {
            byte neighborValue = getParent().getLight(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z, type);
            byte neighborType = getParent().getBlock(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z);

            if (neighborValue < newLightValue - 1 && BlockManager.getInstance().getBlock(neighborType).isTranslucent()) {
                getParent().spreadLight(blockPosX + (int) _lightDirections[i].x, y + (int) _lightDirections[i].y, blockPosZ + (int) _lightDirections[i].z, lightValue, depth + 1, type);
            }
        }
    }

    /**
     * Returns the light intensity at a given local block position.
     *
     * @param x    Local block position on the x-axis
     * @param y    Local block position on the y-axis
     * @param z    Local block position on the z-axis
     * @param type The type of the light
     * @return The light intensity
     */
    public byte getLight(int x, int y, int z, LIGHT_TYPE type) {
        byte result;

        if (type == LIGHT_TYPE.SUN) {
            result = _sunlight.get(x, y, z);
        } else {
            result = _light.get(x, y, z);
        }

        if (result >= 0) {
            return result;
        }

        if (type == Chunk.LIGHT_TYPE.SUN)
            return 15;
        else
            return 0;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x         Local block position on the x-axis
     * @param y         Local block position on the y-axis
     * @param z         Local block position on the z-axis
     * @param intensity The light intensity
     * @param type      The type of the light
     */
    public void setLight(int x, int y, int z, byte intensity, LIGHT_TYPE type) {
        BlockmaniaSmartArray lSource;
        if (type == LIGHT_TYPE.SUN) {
            lSource = _sunlight;
        } else if (type == LIGHT_TYPE.BLOCK) {
            lSource = _light;
        } else {
            return;
        }


        byte oldValue = lSource.get(x, y, z);
        lSource.set(x, y, z, intensity);

        if (oldValue != intensity) {
            setDirty(true);
            // Mark the neighbors as dirty
            markNeighborsDirty(x, z);
        }
    }

    /**
     * Returns the block type at a given local block position.
     *
     * @param x Local block position on the x-axis
     * @param y Local block position on the y-axis
     * @param z Local block position on the z-axis
     * @return The block type
     */
    public byte getBlock(int x, int y, int z) {
        byte result = _blocks.get(x, y, z);

        if (result >= 0) {
            return result;
        }

        return 0;
    }

    public boolean canBlockSeeTheSky(int x, int y, int z) {
        for (int y1 = y; y1 < getChunkDimensionY(); y1++) {
            if (!BlockManager.getInstance().getBlock(getBlock(x, y1, z)).isTranslucent())
                return false;
        }

        return true;
    }

    /**
     * Sets the block value at the given position.
     *
     * @param x    Local block position on the x-axis
     * @param y    Local block position on the y-axis
     * @param z    Local block position on the z-axis
     * @param type The block type
     */
    public void setBlock(int x, int y, int z, byte type) {
        byte oldValue = _blocks.get(x, y, z);
        _blocks.set(x, y, z, type);

        if (oldValue != type) {
            // Update vertex arrays and light
            setDirty(true);
            // Mark the neighbors as dirty
            markNeighborsDirty(x, z);
        }
    }

    /**
     * Calculates the distance of the chunk to the player.
     *
     * @return The distance of the chunk to the player
     */
    public double distanceToPlayer() {
        return Math.sqrt(Math.pow(getParent().getRenderingOrigin().x - getChunkWorldPosX(), 2) + Math.pow(getParent().getRenderingOrigin().z - getChunkWorldPosZ(), 2));
    }

    /**
     * Returns the neighbor chunks of this chunk.
     *
     * @return The adjacent chunks
     */
    public Chunk[] loadOrCreateNeighbors() {
        Chunk[] chunks = new Chunk[8];

        chunks[0] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x + 1, (int) _position.z);
        chunks[1] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x - 1, (int) _position.z);
        chunks[2] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x, (int) _position.z + 1);
        chunks[3] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x, (int) _position.z - 1);
        chunks[4] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x + 1, (int) _position.z + 1);
        chunks[5] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x - 1, (int) _position.z - 1);
        chunks[6] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x - 1, (int) _position.z + 1);
        chunks[7] = getParent().getChunkProvider().loadOrCreateChunk((int) _position.x + 1, (int) _position.z - 1);
        return chunks;
    }

    /**
     * Marks those neighbors of a chunk dirty, that are adjacent to
     * the given block coordinate.
     *
     * @param x Local block position on the x-axis
     * @param z Local block position on the z-axis
     */
    private void markNeighborsDirty(int x, int z) {
        Chunk[] neighbors = loadOrCreateNeighbors();

        if (x == 0 && neighbors[1] != null) {
            neighbors[1].setDirty(true);
        }

        if (x == getChunkDimensionX() - 1 && neighbors[0] != null) {
            neighbors[0].setDirty(true);
        }

        if (z == 0 && neighbors[3] != null) {
            neighbors[3].setDirty(true);
        }

        if (z == getChunkDimensionZ() - 1 && neighbors[2] != null) {
            neighbors[2].setDirty(true);
        }

        if (x == getChunkDimensionX() - 1 && z == 0 && neighbors[7] != null) {
            neighbors[7].setDirty(true);
        }

        if (x == 0 && z == getChunkDimensionZ() - 1 && neighbors[6] != null) {
            neighbors[6].setDirty(true);
        }

        if (x == 0 && z == 0 && neighbors[5] != null) {
            neighbors[5].setDirty(true);
        }

        if (x == getChunkDimensionX() - 1 && z == getChunkDimensionZ() - 1 && neighbors[4] != null) {
            neighbors[4].setDirty(true);
        }
    }

    @Override
    public String toString() {
        return String.format("Chunk at %s.", _position);
    }

    /**
     * Chunks are comparable by their relative distance to the player.
     *
     * @param o The chunk to compare to
     * @return The comparison value
     */
    public int compareTo(Chunk o) {
        if (o == null) {
            return 0;
        }

        double distance = distanceToPlayer();
        double distance2 = o.distanceToPlayer();

        if (distance == distance2)
            return 0;

        return distance2 > distance ? -1 : 1;
    }

    public AABB getAABB() {
        if (_aabb == null) {
            Vector3f dimensions = new Vector3f(getChunkDimensionX() / 2, getChunkDimensionY() / 2, getChunkDimensionZ() / 2);
            Vector3f position = new Vector3f(getChunkWorldPosX() + dimensions.getX(), dimensions.getY(), getChunkWorldPosZ() + dimensions.getZ());
            _aabb = new AABB(position, dimensions);
        }
        return _aabb;
    }

    public boolean processChunk() {
        /*
        * Generate the chunk...
        */
        generate();

        /*
        * ... and fetch its neighbors...
        */
        Chunk[] neighbors = loadOrCreateNeighbors();

        /*
        * Before starting the illumination process, make sure that the neighbor chunks
        * are present and generated.
        */
        for (int i = 0; i < neighbors.length; i++) {
            if (neighbors[i] != null) {
                neighbors[i].generate();
            }
        }

        /*
        * If the light of this chunk is marked as dirty...
        */
        if (isLightDirty()) {
            /*
            * ... propagate light into adjacent chunks...
            */
            updateLight();
        }

        /*
        * Check if this chunk was changed...
        */
        if (isDirty() && !isLightDirty() && !isFresh()) {
            /*
            * ... if yes, regenerate the vertex arrays
            */
            generateMesh();
            return true;
        }

        return false;
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt((int) _position.getX());
        out.writeInt((int) _position.getZ());

        // Save flags...
        byte flags = 0x0;
        if (_lightDirty) {
            flags = Helper.setFlag(flags, (short) 0);
        }

        // The flags are stored within the first byte of the file...
        out.writeByte(flags);

        for (int i = 0; i < _blocks.getSize(); i++)
            out.writeByte(_blocks.getRawByte(i));

        for (int i = 0; i < _sunlight.getPackedSize(); i++)
            out.writeByte(_sunlight.getRawByte(i));

        for (int i = 0; i < _light.getPackedSize(); i++)
            out.writeByte(_light.getRawByte(i));
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        _position.setX(in.readInt());
        _position.setZ(in.readInt());

        // The first byte contains the flags...
        byte flags = in.readByte();
        // Parse the flags...
        _lightDirty = Helper.isFlagSet(flags, (short) 0);

        for (int i = 0; i < _blocks.getSize(); i++)
            _blocks.setRawByte(i, in.readByte());

        for (int i = 0; i < _sunlight.getPackedSize(); i++)
            _sunlight.setRawByte(i, in.readByte());

        for (int i = 0; i < _light.getPackedSize(); i++)
            _light.setRawByte(i, in.readByte());

        _fresh = false;
        _dirty = true;
    }

    /**
     * Generates the terrain mesh (creates the internal vertex arrays).
     */
    public void generateMesh() {
        if (_fresh)
            return;

        setNewMesh(_meshGenerator.generateMesh());
        setDirty(false);
    }


    /**
     * Generates the display lists and swaps the old mesh with the current mesh.
     */
    public void generateVBOs() {
        if (_newMesh != null) {
            _newMesh.generateVBOs();
        }
    }


    /**
     * Draws the opaque or translucent elements of a chunk.
     *
     * @param type The type of vertices to render
     */
    public void render(ChunkMesh.RENDER_TYPE type) {
        // Render the generated chunk mesh
        if (_activeMesh != null) {
            _activeMesh.render(type);
        }
    }

    public void update() {
        swapActiveMesh();
    }

    public void render() {
        // Nothing to do
    }

    private void setNewMesh(ChunkMesh newMesh) {
        synchronized (this) {
            if (_disposed)
                return;

            ChunkMesh oldNewMesh = _newMesh;
            _newMesh = newMesh;

            if (oldNewMesh != null) {
                oldNewMesh.dispose();
            }
        }
    }

    private void swapActiveMesh() {
        if (isDirty() || isFresh() || isLightDirty())
            return;

        synchronized (this) {
            if (_disposed)
                return;

            if (_newMesh != null) {
                if (!_newMesh.isGenerated() || _newMesh.isDisposed())
                    return;

                ChunkMesh newMesh = _newMesh;
                _newMesh = null;

                ChunkMesh oldActiveMesh = _activeMesh;
                _activeMesh = newMesh;

                if (oldActiveMesh != null)
                    oldActiveMesh.dispose();
            }
        }
    }

    /**
     * Returns the position of the chunk within the world.
     *
     * @return The world position
     */

    public int getChunkWorldPosX() {
        return (int) _position.x * getChunkDimensionX();
    }

    /**
     * Returns the position of the chunk within the world.
     *
     * @return Thew world position
     */
    public int getChunkWorldPosZ() {
        return (int) _position.z * (int) getChunkDimensionZ();
    }

    /**
     * Returns the position of block within the world.
     *
     * @param x The local position
     * @return The world position
     */
    public int getBlockWorldPosX(int x) {
        return x + getChunkWorldPosX();
    }

    /**
     * Returns the position of block within the world.
     *
     * @param z The local position
     * @return The world position
     */
    public int getBlockWorldPosZ(int z) {
        return z + getChunkWorldPosZ();
    }

    public LocalWorldProvider getParent() {
        return _parent;
    }

    public boolean isDirty() {
        return _dirty;
    }

    public boolean isFresh() {
        return _fresh;
    }

    public boolean isLightDirty() {
        return _lightDirty;
    }

    void setDirty(boolean _dirty) {
        this._dirty = _dirty;
    }

    void setLightDirty(boolean _lightDirty) {
        this._lightDirty = _lightDirty;
    }

    public void setPosition(Vector3f position) {
        super.setPosition(position);
    }

    public void setParent(LocalWorldProvider parent) {
        _parent = parent;
    }

    public static String getChunkSavePathForPosition(Vector3f position) {
        String x36 = Integer.toString((int) position.x, 36);
        String z36 = Integer.toString((int) position.z, 36);

        return x36 + "/" + z36;
    }

    public static String getChunkFileNameForPosition(Vector3f position) {
        String x36 = Integer.toString((int) position.x, 36);
        String z36 = Integer.toString((int) position.z, 36);

        return "bc_" + x36 + "." + z36;
    }

    public String getChunkSavePath() {
        return Chunk.getChunkSavePathForPosition(_position);
    }

    public String getChunkFileName() {
        return Chunk.getChunkFileNameForPosition(_position);
    }

    public void setVisible(boolean visible) {
        _visible = visible;
    }

    /**
     * Returns true if this chunk is currently visible.
     *
     * @return
     */
    public boolean isVisible() {
        return _visible;
    }

    /**
     * Disposes this chunk. Can NOT be undone.
     */
    public void dispose() {
        synchronized (this) {
            if (_disposed)
                return;

            if (_activeMesh != null)
                _activeMesh.dispose();
            if (_newMesh != null) {
                _newMesh.dispose();
            }

            _disposed = true;
        }
    }
}

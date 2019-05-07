package griddb;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import rasterunit.RasterUnit;
import rasterunit.Tile;
import rasterunit.TileKey;
import util.Range2d;
import util.collections.ReadonlyNavigableSetView;
import util.collections.vec.ReadonlyVecView;
import util.collections.vec.Vec;
import util.yaml.YamlMap;

public class GridDB implements AutoCloseable {
	private static final Logger log = LogManager.getLogger();

	private static final int TILE_TYPE_CELL = 0;

	private final Vec<Attribute> attributes = new Vec<>();

	private RasterUnit rasterUnit = null;

	private final Path path;
	private final Path metaPath;
	public final File metaFile;
	private final Path metaPathTemp;
	private final File metaFileTemp;

	private ExtendedMeta extendedMeta = null;

	private final String fileDataName;
	private final String fileDataCache;
	private final boolean transaction;

	public interface ExtendedMeta {
		public void read(YamlMap map);
		public void write(LinkedHashMap<String, Object> map);
	}

	public GridDB(Path path, String name, ExtendedMeta extendedMeta, boolean transaction) {		
		this.path = path;
		this.transaction =  transaction;
		path.toFile().mkdirs();
		this.extendedMeta = extendedMeta;
		this.fileDataName = name + ".dat";
		this.fileDataCache = name + ".idx"; 
		String fileMetaName = name + ".yml";
		metaPath = path.resolve(fileMetaName);
		metaFile = metaPath.toFile();
		metaPathTemp = Paths.get(metaPath.toString()+"_temp");
		metaFileTemp = metaPathTemp.toFile();
	}

	private RasterUnit rasterUnit() {
		RasterUnit r = rasterUnit;
		return r == null ? loadRasterUnit() : r;
	}

	private synchronized RasterUnit loadRasterUnit() {
		if (rasterUnit == null) {
			rasterUnit = new RasterUnit(path, fileDataName, fileDataCache, !transaction);
		}
		return rasterUnit;
	}

	public void setExtendedMeta(ExtendedMeta extendedMeta) {
		this.extendedMeta = extendedMeta;
	}

	public static Cell tileToCell(Tile tile) {
		try {
			return new Cell(tile.x, tile.y, tile.data);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public Tile createTile(int cx, int cy, byte[] cellData) throws IOException {		
		return new Tile(0, 0, cy, cx, TILE_TYPE_CELL, cellData);		
	}

	public void writeTile(Tile tile) {
		rasterUnit().write(tile);
	}

	public synchronized void commit() {
		if (rasterUnit != null) {
			rasterUnit.commit();
		}
	}

	@Override
	public synchronized void close() {
		if (rasterUnit != null) {
			rasterUnit.close();
			rasterUnit = null;
		}
	}

	private Tile getTile(int tx, int ty) {
		return rasterUnit().getTile(0, 0, ty, tx);		
	}

	public Cell getCell(int x, int y) throws IOException {
		Tile tile = getTile(x, y);
		return tile == null ? null : tileToCell(tile);
	}

	public Collection<Tile> getTiles(int xcellmin, int ycellmin, int xcellmax, int ycellmax) {
		return rasterUnit().getTiles(0, 0, ycellmin, ycellmax, xcellmin, xcellmax);
	}

	public Stream<Cell> getCells(int xcellmin, int ycellmin, int xcellmax, int ycellmax) {
		Collection<Tile> tiles = getTiles(xcellmin, ycellmin, xcellmax, ycellmax);
		/*Spliterator<Tile> spliterator = Spliterators.spliterator(tiles, 0);  // spliterator.trySplit() may allocate very big arrays TODO check
		Stream<Cell> stream = StreamSupport.stream(spliterator, true).map(tile -> tileToCell(tile));*/
		/*Spliterator<Tile> spliterator = new TileSpliterator<Tile>(tiles.iterator(), tiles.size(), 0);
		Stream<Cell> stream = StreamSupport.stream(spliterator, true).map(tile -> tileToCell(tile));*/
		Stream<Cell> stream = tiles.parallelStream().map(tile -> tileToCell(tile));
		//Stream<Cell> stream = tiles.stream().map(tile -> tileToCell(tile));
		return stream;
	}

	public Attribute getAttribute(String name) {
		for(Attribute attr:attributes) {
			if(attr.name.equals(name)) {
				return attr;
			}
		}
		return null;
	}

	public synchronized void addAttribute(String name, int encoding) {
		Attribute prev = getAttribute(name);
		if(prev != null) {
			log.warn("attribute already inserted "+name);
		} else {
			int id = attributes.size();
			if(id>127) {
				throw new RuntimeException("id overflow");
			}
			attributes.add(new Attribute((byte)id, encoding, name));
		}
	}

	public synchronized Attribute getOrAddAttribute(String name, int encoding) {
		Attribute attr = getAttribute(name);
		if(attr == null) {
			addAttribute(name, encoding);
			attr = getAttribute(name);
		}
		return attr;
	}

	public synchronized LinkedHashMap<String, Object> metaToYaml() {
		LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
		LinkedHashMap<Integer, Object> attrMap = new LinkedHashMap<Integer, Object>();
		for(Attribute attr:attributes) {
			attrMap.put((int) attr.id, attr.toYamlWithoutId());
		}
		map.put("attributes", attrMap);
		if(extendedMeta != null) {
			extendedMeta.write(map);
		}
		return map;
	}

	public synchronized void writeMeta() {
		try {
			LinkedHashMap<String, Object> map = metaToYaml();
			Yaml yaml = new Yaml();
			PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(metaFileTemp)));
			yaml.dump(map, out);
			out.close();
			Files.move(metaPathTemp, metaPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			log.warn(e);
		}
	}

	public synchronized void yamlToMeta(YamlMap map) {
		attributes.clear();
		if (map.contains("attributes")) {
			Map<Number, Object> m = (Map<Number, Object>) map.getObject("attributes");
			for (Entry<Number, Object> entry : m.entrySet()) {
				int id = entry.getKey().intValue();
				YamlMap em = YamlMap.ofObject(entry.getValue());
				Attribute attribute = Attribute.ofYaml(em, id);
				attributes.add(attribute);
			}
		}
		if(extendedMeta != null) {
			extendedMeta.read(map);
		}
	}

	public synchronized void readMeta() { // throws if read error
		try {
			if (metaPath.toFile().exists()) {
				YamlMap map;
				try(InputStream in = new FileInputStream(metaFile)) {
					map = YamlMap.ofObject(new Yaml().load(in));
				}
				yamlToMeta(map);
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.warn(e);
			throw new RuntimeException(e);
		}
	}

	public boolean isEmpty() {
		return rasterUnit().isEmpty();
	}

	public Range2d getTileRange() {
		return rasterUnit().getTileRange();
	}

	public ReadonlyNavigableSetView<TileKey> getTileKeys() {
		return rasterUnit().tileKeysReadonly;
	}

	public ReadonlyVecView<Attribute> getAttributes() {
		return attributes.readonlyView();
	}
}
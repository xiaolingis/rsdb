package server.api.rasterdb;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.json.JSONObject;
import org.json.JSONWriter;


import broker.Broker;
import broker.TimeSlice;
import rasterdb.Band;
import rasterdb.RasterDB;
import rasterdb.TimeBand;
import rasterdb.TimeBandProcessor;
import server.api.rasterdb.RequestProcessor.OutputProcessingType;
import util.JsonUtil;
import util.Range2d;
import util.StreamReceiver;
import util.TimeUtil;
import util.Web;

public class RasterdbMethod_packages extends RasterdbMethod {
	private static final Logger log = LogManager.getLogger();

	private final Map<Long, Spec> specMap = new ConcurrentHashMap<>();

	private static class Spec {

		private static AtomicLong idCounter = new AtomicLong(0);

		public final long id = idCounter.incrementAndGet();
		public final RasterDB rasterdb;
		public double[] ext = null;
		public Collection<Band> bands = null;
		public Collection<Integer> time_slice_ids = null;
		public int compression = 0;
		public int div = 1;
		public String arrangement = "multiband";

		public Spec(RasterDB rasterdb) {
			this.rasterdb = rasterdb;
		}

		public String getZipFileUrl() {
			return id + ".zip";
		}

		public String getZipFileName() {
			return rasterdb.config.getName() + ".zip";
		}
	}

	public RasterdbMethod_packages(Broker broker) {
		super(broker, "packages");	
	}

	@Override
	public void handle(RasterDB rasterdb, String target, Request request, Response response, UserIdentity userIdentity) throws IOException {
		log.info(request);
		request.setHandled(true);
		String reqMethod = request.getMethod();
		switch(reqMethod) {
		case "POST":
			post(rasterdb, target, request, response, userIdentity);
			break;
		case "GET":
			get(rasterdb, target, request, response, userIdentity);
			break;
		default:
			throw new RuntimeException("unknown request method: "  + reqMethod);
		}


	}

	private void post(RasterDB rasterdb, String target, Request request, Response response, UserIdentity userIdentity) throws IOException {
		try {
			JSONObject json = new JSONObject(Web.requestContentToString(request));
			JSONObject meta = json.getJSONObject("package");
			log.info(meta);
			meta.getJSONArray("ext");
			Spec spec = new Spec(rasterdb);
			double[] ext = JsonUtil.getDoubleArray(meta, "ext");
			if(ext.length != 4) {
				throw new RuntimeException();
			}
			spec.ext = ext;
			if(meta.has("bands")) {
				int[] bands = JsonUtil.getIntArray(meta, "bands");				
				Collection<Band> processingBands = Arrays.stream(bands).mapToObj(i -> {
					Band band = rasterdb.getBandByNumber(i);
					if (band == null) {
						throw new RuntimeException("band not found: " + i);
					}
					return band;
				}).collect(Collectors.toList());
				spec.bands = processingBands;
			} else {
				spec.bands = rasterdb.bandMapReadonly.values();
			}

			if(meta.has("time_slice_ids")) {
				spec.time_slice_ids = JsonUtil.getIntegerVec(meta, "time_slice_ids");
			} else {
				spec.time_slice_ids = rasterdb.rasterUnit().timeKeysReadonly();
			}

			if(meta.has("compression")) {
				spec.compression = JsonUtil.getInt(meta, "compression");
			}

			if(meta.has("arrangement")) {
				spec.arrangement  = JsonUtil.getString(meta, "arrangement");
			}

			if(meta.has("div")) {
				spec.div = JsonUtil.getInt(meta, "div");
			}

			specMap.put(spec.id, spec);

			response.setContentType(MIME_JSON);
			JSONWriter jsonResponse = new JSONWriter(response.getWriter());
			jsonResponse.object();
			jsonResponse.key("package");
			jsonResponse.object();
			jsonResponse.key("zip_file_url");
			jsonResponse.value(spec.getZipFileUrl());
			jsonResponse.key("zip_file_name");
			jsonResponse.value(spec.getZipFileName());
			jsonResponse.endObject();
			jsonResponse.endObject();
		} catch(Exception e) {
			e.printStackTrace();
			log.error(e);			
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

			response.setContentType(MIME_JSON);
			JSONWriter json = new JSONWriter(response.getWriter());
			json.object();
			json.key("response");
			json.object();
			json.endObject();
			json.endObject();
		} 			
	}

	private void get(RasterDB rasterdb, String target, Request request, Response response, UserIdentity userIdentity) throws IOException {	
		String name = rasterdb.config.getName();
		long id = Long.parseLong(target.replace(".zip", ""));
		Spec spec = specMap.get(id);
		Range2d range2d = rasterdb.ref().bboxToRange2d(spec.ext);
		ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream());
		/*if(spec.compression == 0) {
			zipOutputStream.setMethod(ZipOutputStream.STORED); //not valid: size and crc32 of entry needs to be known
		} else {
			zipOutputStream.setMethod(ZipOutputStream.DEFLATED);
		}*/
		zipOutputStream.setLevel(spec.compression);
		boolean tiled = true;
		if(tiled) {
			int tileSize = 4096 * spec.div;
			range2d.tiled(tileSize, tileSize, (int xtile, int ytile, Range2d tile_range2d) -> {
				String tile_name = name + "__" + xtile + "_" + ytile;
				TimeBandProcessor processor = new TimeBandProcessor(rasterdb, tile_range2d, spec.div);
				try {
					process(tile_name, spec, processor, zipOutputStream);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		} else {
			TimeBandProcessor processor = new TimeBandProcessor(rasterdb, range2d, spec.div);
			process(name, spec, processor, zipOutputStream);
		}
		zipOutputStream.finish();
		zipOutputStream.flush();
	}

	private static String timeSliceIdToText(RasterDB rasterdb, int id) {
		TimeSlice timeSlice = rasterdb.timeMapReadonly.get(id);
		if(timeSlice != null) {
			return timeSlice.name;
		} else {
			return TimeUtil.toFileText(id);
		}

	}

	private void process(String name, Spec spec, TimeBandProcessor processor, ZipOutputStream zipOutputStream) throws IOException {		
		OutputProcessingType outputProcessingType = OutputProcessingType.IDENTITY;
		String format = "tiff";

		switch (spec.arrangement) {
		case "multiband": {
			for(int timestamp:spec.time_slice_ids) {			
				zipOutputStream.putNextEntry(new ZipEntry(name + "__" + timeSliceIdToText(spec.rasterdb, timestamp) + ".tiff"));
				List<TimeBand> timebands = TimeBand.of(timestamp, spec.bands);
				RequestProcessorBands.processBands(processor, timebands, outputProcessingType, format, new StreamReceiver(zipOutputStream));			
				zipOutputStream.closeEntry();			
			}
			break;
		}
		case "timeseries": {
			for(Band band: spec.bands) {
				zipOutputStream.putNextEntry(new ZipEntry(name + "__band_" + band.index + ".tiff"));
				List<TimeBand> timebands = spec.time_slice_ids.stream().map(timestamp -> new TimeBand(timestamp, band)).collect(Collectors.toList());
				RequestProcessorBands.processBands(processor, timebands, outputProcessingType, format, new StreamReceiver(zipOutputStream));			
				zipOutputStream.closeEntry();			
			}
			break;
		}
		case "separate_timestamp_band": {
			for(int timestamp:spec.time_slice_ids) {
				String tfile = name + "__" + timeSliceIdToText(spec.rasterdb, timestamp);
				for(Band band: spec.bands) {
					zipOutputStream.putNextEntry(new ZipEntry(tfile + "__band_" + band.index + ".tiff"));
					Set<TimeBand> timebands = java.util.Collections.singleton(new TimeBand(timestamp, band));
					RequestProcessorBands.processBands(processor, timebands, outputProcessingType, format, new StreamReceiver(zipOutputStream));			
					zipOutputStream.closeEntry();			
				}
			}
			break;		
		}
		case "separate_band_timestamp": {
			for(Band band: spec.bands) {
				String bfile = name + "__band_" + band.index;
				for(int timestamp:spec.time_slice_ids) {			
					zipOutputStream.putNextEntry(new ZipEntry(bfile + "__" + timeSliceIdToText(spec.rasterdb, timestamp) + ".tiff"));
					Set<TimeBand> timebands = java.util.Collections.singleton(new TimeBand(timestamp, band));
					RequestProcessorBands.processBands(processor, timebands, outputProcessingType, format, new StreamReceiver(zipOutputStream));			
					zipOutputStream.closeEntry();			
				}
			}
			break;		
		}
		default:
			throw new RuntimeException("unknown arrangement: "+spec.arrangement);
		}		
	}
}
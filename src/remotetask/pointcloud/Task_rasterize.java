package remotetask.pointcloud;

import org.json.JSONObject;

import broker.Broker;
import pointcloud.PointCloud;
import rasterdb.RasterDB;
import remotetask.Context;
import remotetask.RemoteTask;

@task_pointcloud("rasterize")
public class Task_rasterize extends RemoteTask {
	//private static final Logger log = LogManager.getLogger();

	private final Broker broker;
	private final JSONObject task;

	public Task_rasterize(Context ctx) {
		this.broker = ctx.broker;
		this.task = ctx.task;
	}

	@Override
	public void process() {
		String name = task.getString("pointcloud");
		PointCloud pointcloud = broker.getPointCloud(name);
		String rasterdb_name = task.getString("rasterdb");
		boolean transactions = true;
		if(task.has("transactions")) {
			transactions = task.getBoolean("transactions");
		}
		RasterDB rasterdb = broker.createRasterdb(rasterdb_name, transactions);		
		pointcloud.Rasterizer rasterizer = new pointcloud.Rasterizer(pointcloud, rasterdb);
		rasterizer.run();
		rasterdb.commit();
		rasterdb.rebuildPyramid();
	}
}

package server.api.vectordbs;

import util.collections.ReadonlyList;

public class VectordbDetails {
	
	public String epsg = "";
	public String proj4 = "";	
	public ReadonlyList<String> attributes = ReadonlyList.EMPTY;

}

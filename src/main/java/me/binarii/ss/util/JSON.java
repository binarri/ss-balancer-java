package me.binarii.ss.util;

import com.google.gson.Gson;

public class JSON {

	private static ThreadLocal<Gson> gson = ThreadLocal.withInitial(Gson::new);

	public static String toJSONString(Object obj) {
		return gson.get().toJson(obj);
	}

}

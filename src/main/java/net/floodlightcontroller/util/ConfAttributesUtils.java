package net.floodlightcontroller.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 反射工具类
 * @author hk
 *
 */
@SuppressWarnings("all")
public class ConfAttributesUtils {

	/**
	 * 从json中转换出指定目标类的，对应属性名称的对应类型属性值
	 * @param targetClass 要设置默认属性的目标类
	 * @param fmJson 属性json
	 * @throws Exception
	 */
	public static void setDefaultAttributesFromJson(Class targetClass,String fmJson) throws Exception{
		JsonArray jsonAttributes = new JsonParser().parse(fmJson).getAsJsonArray();
		Iterator<JsonElement> elemnts = jsonAttributes.iterator();
		while(elemnts.hasNext()) {
			JsonObject jo = elemnts.next().getAsJsonObject();
			if(jo.get("name") == null || jo.get("value") == null) continue;
			//获取String类型的属性值，这里传入的json中的name和value都是String类
			String value = jo.get("value").getAsString();
			//通过json中name属性设置的类中属性名获取对应名称的字段属性
			Field f = targetClass.getDeclaredField(jo.get("name").getAsString());
			f.setAccessible(true);//避免访问受限额外处理
			//获取对应属性的类型
			Class type = f.getType();
			String fName="valueOf";//利用包装器类的valueOf
			if(type.isPrimitive()) {//内部数据类型额外处理
				switch (type.getName()) {
				case "byte":
					type =Byte.class;
					break;
				case "short":
					type =Short.class;
					break;
				case "int":
					type =Integer.class;
					break;
				case "long":
					type =Long.class;
					break;
				case "float":
					type =Float.class;
					break;
				case "double":
					type =Double.class;
					break;
				case "boolean":
					type =Boolean.class;
					break;
				case "char":
					type =Character.class;
					break;
				default:
					break;
				}
			}
			//获取对应属性类型的转换类型函数
			Method valueof = type.getDeclaredMethod(fName, String.class);
			//第一个参数是属性或函数所属的类，第2个参数是value值
			f.set(targetClass, valueof.invoke(type,value));		
		}
		return;
	}
}


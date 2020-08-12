package edu.wisc.game.sql;

import java.util.*;
import java.text.*;
import javax.persistence.*;
import javax.json.*;

import java.lang.reflect.*;
import edu.wisc.game.util.Logging;

public class JsonReflect {

    private static JsonArrayBuilder doCollection(Collection col, boolean skipNulls) {
	JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

	//	System.out.println("doCollection, size=" + col.size());
	
	for(Object g: col) {

	    //System.out.println("doCollection, element=" + g);
	    if (g==null) {
		if (skipNulls) continue;
		else arrayBuilder.addNull();
	    } else if (g instanceof String) {
		arrayBuilder.add( (String)g);
	    } else if (g instanceof Integer) {
		arrayBuilder.add( (Integer)g);
	    } else if (g instanceof Long) {
		arrayBuilder.add( (Long)g);
	    } else if (g instanceof Float) {
		arrayBuilder.add( (Float)g);
	    } else if (g instanceof Double) {
		arrayBuilder.add( (Double)g);
	    } else if (g instanceof Array) { // an array
		JsonArrayBuilder x = doCollection(array2vector((Array)g), skipNulls);
		arrayBuilder.add(x);		
	    } else if (g instanceof Collection) {
		JsonArrayBuilder ab = doCollection((Collection)g, skipNulls);
		arrayBuilder.add(ab);
	    } else { // some object
		JsonObjectBuilder ob = reflectToJSON(g, skipNulls);
		arrayBuilder.add(ob);
	    }
	}
	return  arrayBuilder;
    }
    

    /** Convert an object to a JSON object, to the extent possible */
    public static JsonObjectBuilder reflectToJSON(Object o, boolean skipNulls) {
	
	JsonObjectBuilder objectBuilder = Json.createObjectBuilder();
       
	Reflect r = Reflect.getReflect(  o.getClass());
	//Logging.info("Reflecting on " + o.getClass() +"; reflect=" + r + ", has " + r.entries.length + " entries");
	for(Reflect.Entry e: r.entries) {
	    if (o instanceof OurTable &&((OurTable)o).ignores(e.name)) continue;
	    Object val = null;
	    try {
		val = e.g.invoke(o);
	    } catch (IllegalAccessException ex) {
		Logging.error(ex.getMessage());
		val = "ACCESS_ERROR";
	    } catch (InvocationTargetException ex) {
		Logging.error(ex.getMessage());
		val = "INVOCATION_TARGET_ERROR";
	    }

	    if (skipNulls && val==null || val.toString().equals("")) continue;
	    if (skipNulls && e.name.equals("version")) continue;

	    Class c = val.getClass();
	    //System.out.println("name=" + e.name +", val("+c+"; "+c.getName()+"; isArray"+c.isArray()+")=" + val);


	    
	    if (val instanceof String) {
		objectBuilder.add(e.name, (String)val);
	    } else if (val instanceof Integer) {
		objectBuilder.add(e.name, (Integer)val);
	    } else if (val instanceof Long) {
		objectBuilder.add(e.name, (Long)val);
	    } else if (val instanceof Float) {
		objectBuilder.add(e.name, (Float)val);
	    } else if (val instanceof Double) {
		objectBuilder.add(e.name, (Double)val);
	    } else if (val instanceof Enum) {
		objectBuilder.add(e.name, val.toString());
	    } else if (val instanceof Array) { // an array
		//System.out.println("Array name=" + e.name +", size=" +Array.getLength(val));
		JsonArrayBuilder ab = doCollection(array2vector((Array)val), skipNulls);

		objectBuilder.add(e.name,ab);
	    } else if (c.isArray() && c.isInstance(new int[0])) { // an array int[]
		//System.out.println("int[] name=" + e.name);
		JsonArrayBuilder ab = doCollection(arrayInt2vector((int[])val), skipNulls);

		objectBuilder.add(e.name,ab);
		
	    } else if (val instanceof Collection) {
		Collection col = (Collection)val;

		JsonArrayBuilder ab = doCollection(col, skipNulls);
		objectBuilder.add(e.name, ab);
	    } else { // some object
		System.out.println("Treating ("+val+") as 'some object'");
		JsonObjectBuilder ob =  reflectToJSON(val, skipNulls);
		objectBuilder.add(e.name, ob);
	    }
	}
	return objectBuilder;
    }

    static private Vector array2vector(Array a) {
	Vector v = new Vector();
	for(int i=0; i<Array.getLength(a); i++) {
	    v.add( Array.get(a,i));
	}
	return v;
    }

    // FIXME: need this for other primitive types too!
    static private Vector arrayInt2vector(int[] a) {
	Vector v = new Vector();
	for(int i=0; i<a.length; i++) {
	    v.add(a[i]);
	}
	return v;
    }


    
    /** Convert an object to a JSON object, to the extent possible */
    public static JsonObject reflectToJSONObject(Object o, boolean skipNulls) {
	return reflectToJSON(o, skipNulls).build();
    }


    
}
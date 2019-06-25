package net.floodlightcontroller.util;

public class ValidatorUtil {
	/**
	 * 包名校验器
	 * @param packageName
	 * @return
	 */
	private boolean vaildPackageName(String packageName) {
		if(packageName == null || !packageName.matches("[\\\\w]+(\\\\.[\\\\w]+)*")) {
			return false;
		}
		return true;
	}
}

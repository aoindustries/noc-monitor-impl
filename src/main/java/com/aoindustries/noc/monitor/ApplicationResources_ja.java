/*
 * Copyright 2007-2009, 2016, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.util.i18n.EditableResourceBundle;
import java.io.File;
import java.util.Locale;

/**
 * Provides a simplified interface for obtaining localized values from the ApplicationResources.properties files.
 * Is also an editable resource bundle.
 *
 * @author  AO Industries, Inc.
 */
public final class ApplicationResources_ja extends EditableResourceBundle {

	/**
	 * Do not use directly.
	 */
	public ApplicationResources_ja() {
		super(
			Locale.JAPANESE,
			ApplicationResources.bundleSet,
			new File(System.getProperty("user.home")+"/maven2/ao/noc-monitor/src/com/aoindustries/noc/monitor/ApplicationResources_ja.properties")
		);
	}
}

/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.service.urlconversion;

import java.io.IOException;
import java.net.URL;

/**
 * The interface of the service that allows bundleresource or bundleentry
 * URLs to be converted to native file URLs on the local file system.
 */
public interface URLConverter {
	/**
	 * Converts a URL that uses a protocol of bundleentry or bundleresource into
	 * a URL that uses the file protocol.  The contents of the URL may be extracted
	 * into a cache on the file system in order to get a file URL.
	 * @param url The bundleentry or bundleresource URL to convert into a file URL.
	 * @return The converted file URL or the original URL passed in if it is not
	 * a bundleentry or bundleresource URL.
	 * @throws IOException if an error occurs during the conversion.
	 */
	public URL convertToFileURL(URL url) throws IOException;

	/**
	 * Converts a URL that uses a protocol of bundleentry or bundleresource into
	 * a URL that uses a local java protocol (file, jar etc).
	 * @param url The bundleentry or bundleresource URL to convert into a local URL.
	 * @return The converted file URL or the original URL passed in if it is not
	 * a bundleentry or bundleresource URL.
	 * @throws IOException if an error occurs during the conversion.
	 */
	public URL convertToLocalURL(URL url) throws IOException;
}
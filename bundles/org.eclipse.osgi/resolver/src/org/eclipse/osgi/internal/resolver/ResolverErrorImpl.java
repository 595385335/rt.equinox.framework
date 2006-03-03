/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.resolver;

import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.util.NLS;

public class ResolverErrorImpl implements ResolverError {
	private BundleDescriptionImpl bundle;
	private int type;
	private String data;
	private VersionConstraint unsatisfied;
	public ResolverErrorImpl(BundleDescriptionImpl bundle, int type, String data, VersionConstraint unsatisfied) {
		this.bundle = bundle;
		this.data = data;
		this.type = type;
		this.unsatisfied = unsatisfied;
	}

	public BundleDescription getBundle() {
		return bundle;
	}

	public int getType() {
		return type;
	}

	public String getData() {
		return data;
	}

	public VersionConstraint getUnsatisfiedConstraint() {
		return unsatisfied;
	}

	public String toString() {
		switch (getType()) {
			case ResolverError.EXPORT_PACKAGE_PERMISSION :
			case ResolverError.IMPORT_PACKAGE_PERMISSION :
			case ResolverError.REQUIRE_BUNDLE_PERMISSION :
			case ResolverError.PROVIDE_BUNDLE_PERMISSION :
			case ResolverError.FRAGMENT_BUNDLE_PERMISSION :
			case ResolverError.HOST_BUNDLE_PERMISSION :
				return NLS.bind(StateMsg.RES_ERROR_MISSING_PERMISSION, getData());
			case ResolverError.MISSING_IMPORT_PACKAGE :
			case ResolverError.MISSING_REQUIRE_BUNDLE :
			case ResolverError.MISSING_FRAGMENT_HOST :
			case ResolverError.MISSING_EXECUTION_ENVIRONMENT :
				return NLS.bind(StateMsg.RES_ERROR_MISSING_CONSTRAINT, getData());
			case ResolverError.FRAGMENT_CONFLICT :
				return NLS.bind(StateMsg.RES_ERROR_FRAGMENT_CONFLICT, getData());
			case ResolverError.IMPORT_PACKAGE_USES_CONFLICT :
			case ResolverError.REQUIRE_BUNDLE_USES_CONFLICT :
				return NLS.bind(StateMsg.RES_ERROR_USES_CONFLICT, getData());
			case ResolverError.SINGLETON_SELECTION :
				return NLS.bind(StateMsg.RES_ERROR_SINGLETON_CONFLICT, getData());
			case ResolverError.PLATFORM_FILTER :
				return NLS.bind(StateMsg.RES_ERROR_PLATFORM_FILTER, getData());
			default :
				return StateMsg.RES_ERROR_UNKNOWN;
		}
	}
}

/*******************************************************************************
 * Copyright (c) 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.framework.internal.core;

/**
 * This class is used to optimize finding provided-packages for a bundle.
 * If the package cannot be found in a list of required bundles then this class
 * is used to cache a null package source so that the search does not need to
 * be done again.
 */
public class NullPackageSource extends PackageSource {
	public NullPackageSource(String name) {
		this.id = name;
	}

	public BundleLoaderProxy getSupplier() {
		return null;
	}

	public boolean isMultivalued() {
		return false;
	}

	public BundleLoaderProxy[] getSuppliers() {
		return null;
	}

	public boolean isNullSource() {
		return true;
	}

	public String toString() {
		return id + " -> null";
	}
}
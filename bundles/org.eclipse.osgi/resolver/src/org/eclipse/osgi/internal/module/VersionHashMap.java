/*******************************************************************************
 * Copyright (c) 2004, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Danail Nachev -  ProSyst - bug 218625
 *******************************************************************************/
package org.eclipse.osgi.internal.module;

import java.util.*;

public class VersionHashMap extends MappedList implements Comparator {
	private ResolverImpl resolver;

	public VersionHashMap(ResolverImpl resolver) {
		this.resolver = resolver;
	}

	// sorts using the Comparator#compare method to sort
	protected void sort(Object[] values) {
		Arrays.sort(values, this);
	}

	public void put(Object key, Object value) {
		Object[] existing = (Object[]) internal.get(key);
		if (existing == null) {
			existing = new Object[1]; // be optimistic; start small
			existing[0] = value;
			internal.put(key, existing);
		} else {
			// insert the new value maintaining sort order.
			Object[] newValues = new Object[existing.length + 1];
			int index = existing.length;
			if (compare(existing[existing.length - 1], value) > 0) {
				index = Arrays.binarySearch(existing, value, this);
				if (index < 0)
					index = -index - 1;
			}
			System.arraycopy(existing, 0, newValues, 0, index);
			newValues[index] = value;
			System.arraycopy(existing, index, newValues, index + 1, existing.length - index);
			internal.put(key, newValues); // overwrite the old values in the map
		}
	}

	public void put(VersionSupplier[] versionSuppliers) {
		for (int i = 0; i < versionSuppliers.length; i++)
			put(versionSuppliers[i].getName(), versionSuppliers[i]);
	}

	public boolean contains(VersionSupplier vs) {
		return contains(vs, false) != null;
	}

	private VersionSupplier contains(VersionSupplier vs, boolean remove) {
		Object[] existing = (Object[]) internal.get(vs.getName());
		if (existing == null)
			return null;
		for (int i = 0; i < existing.length; i++)
			if (existing[i] == vs) {
				if (remove) {
					if (existing.length == 1) {
						internal.remove(vs.getName());
						return vs;
					}
					Object[] newExisting = new Object[existing.length - 1];
					System.arraycopy(existing, 0, newExisting, 0, i);
					if (i + 1 < existing.length)
						System.arraycopy(existing, i + 1, newExisting, i, existing.length - i - 1);
					internal.put(vs.getName(), newExisting);
				}
				return vs;
			}
		return null;
	}

	public Object remove(VersionSupplier toBeRemoved) {
		return contains(toBeRemoved, true);
	}

	public void remove(VersionSupplier[] versionSuppliers) {
		for (int i = 0; i < versionSuppliers.length; i++)
			remove(versionSuppliers[i]);
	}

	// Once we have resolved bundles, we need to make sure that version suppliers
	// from the resolved bundles are ahead of those from unresolved bundles
	void reorder() {
		for (Iterator it = internal.values().iterator(); it.hasNext();) {
			Object[] existing = (Object[]) it.next();
			if (existing.length <= 1)
				continue;
			sort(existing);
		}
	}

	// Compares two VersionSuppliers for descending ordered sorts.
	// The VersionSuppliers are sorted by the following priorities
	// First the resolution status of the supplying bundle.
	// Second is the supplier version.
	// Third is the bundle id of the supplying bundle.
	public int compare(Object o1, Object o2) {
		if (!(o1 instanceof VersionSupplier) || !(o2 instanceof VersionSupplier))
			throw new IllegalArgumentException();
		VersionSupplier vs1 = (VersionSupplier) o1;
		VersionSupplier vs2 = (VersionSupplier) o2;
		// if the selection policy is set then use that
		if (resolver.getSelectionPolicy() != null)
			return resolver.getSelectionPolicy().compare(vs1.getBaseDescription(), vs2.getBaseDescription());
		String systemBundle = resolver.getSystemBundle();
		if (systemBundle.equals(vs1.getBundle().getSymbolicName()) && !systemBundle.equals(vs2.getBundle().getSymbolicName()))
			return -1;
		else if (!systemBundle.equals(vs1.getBundle().getSymbolicName()) && systemBundle.equals(vs2.getBundle().getSymbolicName()))
			return 1;
		if (vs1.getBundle().isResolved() != vs2.getBundle().isResolved())
			return vs1.getBundle().isResolved() ? -1 : 1;
		int versionCompare = -(vs1.getVersion().compareTo(vs2.getVersion()));
		if (versionCompare != 0)
			return versionCompare;
		return vs1.getBundle().getBundleId() <= vs2.getBundle().getBundleId() ? -1 : 1;
	}
}

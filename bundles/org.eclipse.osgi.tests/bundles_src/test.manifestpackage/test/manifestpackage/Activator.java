/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.manifestpackage;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import test.manifestpackage.a.A;
import test.manifestpackage.b.B;
import test.manifestpackage.c.C;

public class Activator implements BundleActivator {

	public void start(BundleContext context) throws Exception {
		Package aPkg = A.class.getPackage();
		assertNotNull("aPkg", aPkg);
		checkPackage(aPkg, "a", "1.1", "equinox", "A", "11.0", "equinox");
		Package bPkg = B.class.getPackage();
		assertNotNull("bPkg", bPkg);
		checkPackage(bPkg, "b", "1.2", "equinox", "B", "12.0", "equinox");
		Package cPkg = C.class.getPackage();
		assertNotNull("cPkg", cPkg);
		checkPackage(cPkg, "c", "1.3", "equinox", "C", "13.0", "equinox");
	}

	private void checkPackage(Package pkg, String specTitle, String specVersion, String specVendor, String implTitle, String implVersion, String implVendor) {
		assertEquals(specTitle, pkg.getSpecificationTitle());
		assertEquals(specVersion, pkg.getSpecificationVersion());
		assertEquals(specVendor, pkg.getSpecificationVendor());
		assertEquals(implTitle, pkg.getImplementationTitle());
		assertEquals(implVersion, pkg.getImplementationVersion());
		assertEquals(implVendor, pkg.getImplementationVendor());
	}

	private void assertEquals(String expected, String actual) {
		if (!expected.equals(actual))
			throw new RuntimeException("Expected: \"" + expected + "\" but got: \"" + actual + "\"");
	}

	private void assertNotNull(String msg, Package pkg) {
		if (pkg == null)
			throw new RuntimeException(msg);
	}

	public void stop(BundleContext context) throws Exception {
		// nothing
	}
}

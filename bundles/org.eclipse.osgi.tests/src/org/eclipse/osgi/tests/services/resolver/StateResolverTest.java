/*******************************************************************************
 * Copyright (c) 2003, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.tests.services.resolver;

import java.io.File;
import java.io.IOException;
import java.util.*;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.osgi.framework.*;

public class StateResolverTest extends AbstractStateTest {
	public static Test suite() {
		return new TestSuite(StateResolverTest.class);
	}

	public StateResolverTest(String name) {
		super(name);
	}

	/**
	 * Tests adding 3 new bundles to an already resolved state and then
	 * resolving only one of the bundles. The result should be all 3 added
	 * bundles being resolved.
	 *  
	 */
	public void testAdd3Resolve1() throws BundleException {
		State state = buildInitialState();
		StateDelta delta = state.resolve();
		BundleDelta[] deltas = delta.getChanges();
		BundleDescription b0 = state.getBundle(0);
		assertNotNull("0.1", b0);
		assertFullyResolved("0.2", b0);
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p1; specification-version=1.0");
		BundleDescription b1 = null;
		try {
			b1 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b1", 1);
			state.removeBundle(1);
			state.addBundle(b1);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p1");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p2; specification-version=1.0");
		BundleDescription b2 = null;
		try {
			b2 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b2", 2);
			state.removeBundle(2);
			state.addBundle(b2);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b3");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p2");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p3; specification-version=1.0");
		BundleDescription b3 = null;
		try {
			b3 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b3", 3);
			state.removeBundle(3);
			state.addBundle(b3);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		delta = state.resolve(new BundleDescription[] {state.getBundle(1)});
		deltas = delta.getChanges();
		assertEquals("1.0", 3, deltas.length);
		Map deltasMap = new HashMap();
		for (int i = 0; i < deltas.length; i++)
			deltasMap.put(new Long(deltas[i].getBundle().getBundleId()), deltas[i]);
		assertNotNull("1.1", deltasMap.get(new Long(1)));
		assertNotNull("1.2", deltasMap.get(new Long(2)));
		assertNotNull("1.3", deltasMap.get(new Long(3)));
		assertEquals("2.1", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(1))).getType());
		assertEquals("2.2", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(2))).getType());
		assertEquals("2.3", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(3))).getType());
	}

	public void testBasicResolution() throws BundleException {
		State state = buildSimpleState();
		StateDelta delta = state.resolve();
		BundleDescription b1 = state.getBundle(1);
		BundleDescription b2 = state.getBundle(2);
		BundleDescription b3 = state.getBundle(3);
		assertNotNull("0.1", b1);
		assertNotNull("0.2", b2);
		assertNotNull("0.3", b3);
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.0", 3, changes.length);
		BundleDelta[] additions = delta.getChanges(BundleDelta.ADDED, false);
		assertEquals("2.0", 3, additions.length);
		BundleDelta[] resolutions = delta.getChanges(BundleDelta.RESOLVED, false);
		assertEquals("3.0", 2, resolutions.length);
		Map deltasMap = new HashMap();
		for (int i = 0; i < resolutions.length; i++)
			deltasMap.put(resolutions[i].getBundle().getSymbolicName(), resolutions[i]);
		assertNotNull("3.1", deltasMap.get(b1.getSymbolicName()));
		assertNotNull("3.2", deltasMap.get(b2.getSymbolicName()));
		// TODO why do we expect unresolved deltas here when the bundle was not resolved in the first place?
		//BundleDelta[] unresolutions = delta.getChanges(BundleDelta.UNRESOLVED, false);
		//assertEquals("4.0", 1, unresolutions.length);
		//assertEquals("4.1", unresolutions[0].getBundle(), b3);
		assertFullyResolved("5.1", b1);
		assertFullyResolved("5.2", b2);
		assertFullyUnresolved("5.3", b3);
	}

	public void testComplexResolution() throws BundleException {
		State state = buildComplexState();
		StateDelta delta = state.resolve();
		BundleDescription b1 = state.getBundle("org.eclipse.b1", Version.parseVersion("1.0"));
		BundleDescription b2 = state.getBundle("org.eclipse.b2", Version.parseVersion("2.0"));
		BundleDescription b3 = state.getBundle("org.eclipse.b3", Version.parseVersion("2.0"));
		BundleDescription b4 = state.getBundle("org.eclipse.b4", Version.parseVersion("2.0"));
		BundleDescription b5 = state.getBundle("org.eclipse.b5", Version.parseVersion("1.0"));
		BundleDescription b6 = state.getBundle("org.eclipse.b6", Version.parseVersion("1.0"));
		assertNotNull("0.1", b1);
		assertNotNull("0.2", b2);
		assertNotNull("0.3", b3);
		assertNotNull("0.4", b4);
		assertNotNull("0.5", b5);
		assertNotNull("0.6", b6);
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.0", 6, changes.length);
		BundleDelta[] additions = delta.getChanges(BundleDelta.ADDED, false);
		assertEquals("2.0", 6, additions.length);
		BundleDelta[] resolutions = delta.getChanges(BundleDelta.RESOLVED, false);
		assertEquals("3.0", 6, resolutions.length);
		Map deltasMap = new HashMap();
		for (int i = 0; i < resolutions.length; i++)
			deltasMap.put(resolutions[i].getBundle().getSymbolicName(), resolutions[i]);
		assertNotNull("3.1", deltasMap.get(b1.getSymbolicName()));
		assertNotNull("3.2", deltasMap.get(b2.getSymbolicName()));
		assertNotNull("3.3", deltasMap.get(b3.getSymbolicName()));
		assertNotNull("3.4", deltasMap.get(b4.getSymbolicName()));
		assertNotNull("3.5", deltasMap.get(b5.getSymbolicName()));
		assertNotNull("3.6", deltasMap.get(b6.getSymbolicName()));
		BundleDelta[] unresolutions = delta.getChanges(BundleDelta.UNRESOLVED, false);
		assertEquals("4.0", 0, unresolutions.length);
		assertFullyResolved("5.1", b1);
		assertFullyResolved("5.2", b2);
		assertFullyResolved("5.3", b3);
		assertFullyResolved("5.4", b4);
		assertFullyResolved("5.5", b5);
		assertFullyResolved("5.6", b6);
	}

	public void testDependentBundles() throws BundleException {
		State state = buildComplexState();
		state.resolve();
		BundleDescription[] dependent;
		dependent = platformAdmin.getStateHelper().getDependentBundles(new BundleDescription[] {state.getBundle(2)});
		assertEquals("1.0", 1, dependent.length);
		assertEquals("1.1", state.getBundle(2), dependent[0]);
		dependent = platformAdmin.getStateHelper().getDependentBundles(new BundleDescription[] {state.getBundle(1)});
		assertEquals("2.0", 4, dependent.length);
		assertContains("2.1", dependent, state.getBundle(1));
		assertContains("2.2", dependent, state.getBundle(2));
		assertContains("2.3", dependent, state.getBundle(4));
		assertContains("2.4", dependent, state.getBundle(6));
	}

	public void testPrerequisiteBundle() throws BundleException {
		State state = buildComplexState();
		state.resolve();
		BundleDescription[] prereqs;
		prereqs = platformAdmin.getStateHelper().getPrerequisites(state.getResolvedBundles());
		assertEquals("1.0", 6, prereqs.length);
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(1)});
		assertEquals("2.0", 2, prereqs.length);
		assertContains("2.1", prereqs, state.getBundle(1));
		assertContains("2.2", prereqs, state.getBundle(3));
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(2)});
		assertEquals("3.0", 3, prereqs.length);
		assertContains("3.1", prereqs, state.getBundle(1));
		assertContains("3.2", prereqs, state.getBundle(2));
		assertContains("3.3", prereqs, state.getBundle(3));
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(3)});
		assertEquals("4.0", 1, prereqs.length);
		assertContains("4.1", prereqs, state.getBundle(3));
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(4)});
		assertEquals("5.0", 3, prereqs.length);
		assertContains("5.1", prereqs, state.getBundle(1));
		assertContains("5.2", prereqs, state.getBundle(3));
		assertContains("5.3", prereqs, state.getBundle(4));
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(5)});
		assertEquals("6.0", 2, prereqs.length);
		assertContains("6.1", prereqs, state.getBundle(3));
		assertContains("6.2", prereqs, state.getBundle(5));
		prereqs = platformAdmin.getStateHelper().getPrerequisites(new BundleDescription[] {state.getBundle(6)});
		assertEquals("6.0", 4, prereqs.length);
		assertContains("6.1", prereqs, state.getBundle(1));
		assertContains("6.2", prereqs, state.getBundle(3));
		assertContains("6.3", prereqs, state.getBundle(4));
		assertContains("6.4", prereqs, state.getBundle(6));
	}

	// temporarily disabled
	public void testLinkageChange() throws BundleException {
		State state = buildEmptyState();
		// don't add b1 for now
		String B1_LOCATION = "org.eclipse.b1";
		final String B1_MANIFEST = "Bundle-SymbolicName: org.eclipse.b1\n" + "Bundle-Version: 1.0\n";
		BundleDescription b1 = state.getFactory().createBundleDescription(parseManifest(B1_MANIFEST), B1_LOCATION, (long) (Math.random() * Long.MAX_VALUE));
		// b2 requires b1 optionally, so should resolve
		String B2_LOCATION = "org.eclipse.b2";
		final String B2_MANIFEST = "Bundle-SymbolicName: org.eclipse.b2\n" + "Bundle-Version: 2.0\n" + "Require-Bundle: org.eclipse.b1;optional=true";
		BundleDescription b2 = state.getFactory().createBundleDescription(parseManifest(B2_MANIFEST), B2_LOCATION, (long) (Math.random() * Long.MAX_VALUE));
		// b3 requires b1, so should not resolve
		String B3_LOCATION = "org.eclipse.b3";
		final String B3_MANIFEST = "Bundle-SymbolicName: org.eclipse.b3\n" + "Bundle-Version: 2.0\n" + "Require-Bundle: org.eclipse.b1";
		BundleDescription b3 = state.getFactory().createBundleDescription(parseManifest(B3_MANIFEST), B3_LOCATION, (long) (Math.random() * Long.MAX_VALUE));
		// add b2 and b3
		state.addBundle(b2);
		state.addBundle(b3);
		StateDelta delta = state.resolve();
		// ensure we didn't add b1 yet
		assertFullyUnresolved("0.1", b1);
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.0", 2, changes.length);
		BundleDelta[] additions = delta.getChanges(BundleDelta.ADDED, false);
		assertEquals("2.0", 2, additions.length);
		BundleDelta[] resolutions = delta.getChanges(BundleDelta.RESOLVED, false);
		assertEquals("3.0", 1, resolutions.length);
		assertEquals("3.1", b2, resolutions[0].getBundle());
		BundleDelta[] addtionsNotResolved = delta.getChanges(BundleDelta.ADDED, true);
		assertEquals("4.0", 1, addtionsNotResolved.length);
		assertEquals("4.1", b3, addtionsNotResolved[0].getBundle());
		state.addBundle(b1);
		delta = state.resolve();
		changes = delta.getChanges();
		assertEquals("5.0", 2, changes.length);
		additions = delta.getChanges(BundleDelta.ADDED, false);
		assertEquals("6.0", 1, additions.length);
		assertEquals("6.1", b1, additions[0].getBundle());
		resolutions = delta.getChanges(BundleDelta.RESOLVED, false);
		assertEquals("7.0", 2, resolutions.length);
		BundleDelta[] existingResolved = delta.getChanges(BundleDelta.RESOLVED, true);
		assertEquals("8.0", 1, existingResolved.length);
		assertEquals("8.1", b3, existingResolved[0].getBundle());
		// TODO linkage changed types are no longer valid
		//BundleDelta[] optionalLinkageChanged = delta.getChanges(BundleDelta.OPTIONAL_LINKAGE_CHANGED, true);
		//assertEquals("9.0", 1, optionalLinkageChanged.length);
		//assertEquals("9.1", b2, optionalLinkageChanged[0].getBundle());
		delta = state.resolve(new BundleDescription[] {b2});
		changes = delta.getChanges();
		assertEquals("9.0", 1, changes.length);
		resolutions = delta.getChanges(BundleDelta.RESOLVED, true);
		assertEquals("9.1", 1, resolutions.length);
		assertEquals("9.2", b2, resolutions[0].getBundle());
		assertFullyResolved("10.1", b1);
		assertFullyResolved("10.2", b2);
		assertFullyResolved("10.3", b3);
	}

	// temporarily disabled
	public void testReinstall() throws BundleException {
		State state = buildComplexState();
		StateDelta delta = state.resolve();
		// remove bundle 4 - should cause 6 to be unresolved
		state.removeBundle(4);
		delta = state.resolve();
		assertEquals("1.0", 1, delta.getChanges(BundleDelta.REMOVED | BundleDelta.UNRESOLVED | BundleDelta.REMOVAL_COMPLETE, true).length);
		assertEquals("1.1", 4, delta.getChanges(BundleDelta.REMOVED | BundleDelta.UNRESOLVED | BundleDelta.REMOVAL_COMPLETE, true)[0].getBundle().getBundleId());
		assertEquals("2.0", 1, delta.getChanges(BundleDelta.UNRESOLVED, true).length);
		assertEquals("2.1", 6, delta.getChanges(BundleDelta.UNRESOLVED, true)[0].getBundle().getBundleId());
		// reinstall bundle 4 - should cause 6 to be resolved again
		BundleDescription b4 = delta.getChanges(BundleDelta.REMOVED | BundleDelta.UNRESOLVED | BundleDelta.REMOVAL_COMPLETE, true)[0].getBundle();
		state.addBundle(b4);
		delta = state.resolve();
		assertEquals("3.0", 1, delta.getChanges(BundleDelta.ADDED | BundleDelta.RESOLVED, true).length);
		assertEquals("3.1", 4, delta.getChanges(BundleDelta.ADDED | BundleDelta.RESOLVED, true)[0].getBundle().getBundleId());
		assertEquals("4.0", 1, delta.getChanges(BundleDelta.RESOLVED, true).length);
		assertEquals("4.1", 6, delta.getChanges(BundleDelta.RESOLVED, true)[0].getBundle().getBundleId());
	}

	public void testRemoval() throws BundleException {
		String B1_LOCATION = "org.eclipse.b1";
		final String B1_MANIFEST = "Bundle-SymbolicName: org.eclipse.b1\n" + "Bundle-Version: 1.0\n";
		String B2_LOCATION = "org.eclipse.b2";
		final String B2_MANIFEST = "Bundle-SymbolicName: org.eclipse.b2\n" + "Bundle-Version: 1.0\n";
		State state = platformAdmin.getState();
		state.setResolver(platformAdmin.getResolver());
		BundleDescription b1 = platformAdmin.getFactory().createBundleDescription(parseManifest(B1_MANIFEST), B1_LOCATION, 1);
		BundleDescription b2 = platformAdmin.getFactory().createBundleDescription(parseManifest(B2_MANIFEST), B2_LOCATION, 2);
		state.addBundle(b1);
		state.addBundle(b2);
		StateDelta delta = state.resolve();
		assertTrue("1.1", contains(state.getResolvedBundles(), b1));
		assertTrue("1.2", contains(state.getResolvedBundles(), b2));
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.3", 2, changes.length);
		assertEquals("1.4 - " + changes[0].getBundle(), (BundleDelta.ADDED | BundleDelta.RESOLVED), changes[0].getType());
		assertEquals("1.5 - " + changes[1].getBundle(), (BundleDelta.ADDED | BundleDelta.RESOLVED), changes[1].getType());
		assertFullyResolved("1.6", b1);
		assertFullyResolved("1.7", b2);
		// remove a resolved bundle
		state.removeBundle(b1);
		assertTrue("2.0", !contains(state.getResolvedBundles(), b1));
		assertTrue("2.1", contains(state.getResolvedBundles(), b2));
		delta = state.resolve();
		changes = delta.getChanges();
		assertEquals("2.2", 1, changes.length);
		assertEquals("2.3", b1, changes[0].getBundle());
		assertEquals("2.4", BundleDelta.REMOVED | BundleDelta.UNRESOLVED, changes[0].getType());
	}

	public void testRemoveAndAdd() throws BundleException {
		String B_LOCATION = "org.eclipse.b";
		final String B_MANIFEST = "Bundle-SymbolicName: org.eclipse.b\n" + "Bundle-Version: 1.0\n";
		State state = platformAdmin.getState();
		state.setResolver(platformAdmin.getResolver());
		BundleDescription b1 = platformAdmin.getFactory().createBundleDescription(parseManifest(B_MANIFEST), B_LOCATION, 1);
		BundleDescription b2 = platformAdmin.getFactory().createBundleDescription(parseManifest(B_MANIFEST), B_LOCATION, 2);
		state.addBundle(b1);
		StateDelta delta = state.resolve();
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.0", 1, changes.length);
		assertEquals("1.1", b1, changes[0].getBundle());
		assertEquals("1.2", (BundleDelta.ADDED | BundleDelta.RESOLVED), changes[0].getType());
		assertFullyResolved("1.3", b1);
		state.removeBundle(b1);
		state.addBundle(b2);
		delta = state.resolve();
		assertEquals("2.0", 2, delta.getChanges().length);
		assertEquals("2.1", 1, delta.getChanges(BundleDelta.UNRESOLVED | BundleDelta.REMOVED, true).length);
		assertEquals("2.2", b1, delta.getChanges(BundleDelta.UNRESOLVED | BundleDelta.REMOVED, true)[0].getBundle());
		assertEquals("2.3", 1, delta.getChanges(BundleDelta.RESOLVED | BundleDelta.ADDED, true).length);
		assertEquals("2.3", b2, delta.getChanges(BundleDelta.RESOLVED | BundleDelta.ADDED, true)[0].getBundle());
	}

	public void testRemovalResolve() throws BundleException {
		State state = buildInitialState();
		StateDelta delta = state.resolve();
		BundleDelta[] deltas = delta.getChanges();
		BundleDescription b0 = state.getBundle(0);
		assertNotNull("0.1", b0);
		assertFullyResolved("0.2", b0);
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p1; specification-version=1.0");
		BundleDescription b1 = null;
		try {
			b1 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b1", 1);
			state.removeBundle(1);
			state.addBundle(b1);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p1");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p2; specification-version=1.0");
		BundleDescription b2 = null;
		try {
			b2 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b2", 2);
			state.removeBundle(2);
			state.addBundle(b2);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b3");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p2");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p3; specification-version=1.0");
		BundleDescription b3 = null;
		try {
			b3 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b3", 3);
			state.removeBundle(3);
			state.addBundle(b3);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		delta = state.resolve(false);
		deltas = delta.getChanges();
		assertEquals("1.0", 4, deltas.length);
		Map deltasMap = new HashMap();
		for (int i = 0; i < deltas.length; i++)
			deltasMap.put(new Long(deltas[i].getBundle().getBundleId()), deltas[i]);
		assertNotNull("1.1", deltasMap.get(new Long(1)));
		assertNotNull("1.2", deltasMap.get(new Long(2)));
		assertNotNull("1.3", deltasMap.get(new Long(3)));
		assertEquals("2.1", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(1))).getType());
		assertEquals("2.2", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(2))).getType());
		assertEquals("2.3", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(3))).getType());
		state.removeBundle(1);
		delta = state.resolve(false);
		deltas = delta.getChanges();
		b2 = state.getBundle(2);
		b3 = state.getBundle(3);
		assertEquals("3.1", false, b2.isResolved());
		assertEquals("3.2", false, b3.isResolved());
	}

	/**
	 * Tests adding 3 new bundles to an already resolved state and then
	 * resolving only one of the bundles. The result should be all 3 added
	 * bundles being resolved. Then re-resolving the same bundle. The result
	 * should be only the one bundle being resolved.
	 *  
	 */
	public void testReresolveBundle() throws BundleException {
		State state = buildInitialState();
		StateDelta delta = state.resolve();
		BundleDelta[] deltas = delta.getChanges();
		BundleDescription b0 = state.getBundle(0);
		assertNotNull("0.1", b0);
		assertFullyResolved("0.2", b0);
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p1; specification-version=1.0");
		BundleDescription b1 = null;
		try {
			b1 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b1", 1);
			state.removeBundle(1);
			state.addBundle(b1);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p1");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p2; specification-version=1.0");
		BundleDescription b2 = null;
		try {
			b2 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b2", 2);
			state.removeBundle(2);
			state.addBundle(b2);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.b3");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.eclipse.p2");
		manifest.put(Constants.EXPORT_PACKAGE, "org.eclipse.p3; specification-version=1.0");
		BundleDescription b3 = null;
		try {
			b3 = state.getFactory().createBundleDescription(manifest, "org.eclipse.b3", 3);
			state.removeBundle(3);
			state.addBundle(b3);
		} catch (BundleException e) {
			fail("Failed to create BundleDescription: " + e.getMessage());
		}
		delta = state.resolve(new BundleDescription[] {state.getBundle(1)});
		deltas = delta.getChanges();
		assertEquals("1.0", 3, deltas.length);
		Map deltasMap = new HashMap();
		for (int i = 0; i < deltas.length; i++)
			deltasMap.put(new Long(deltas[i].getBundle().getBundleId()), deltas[i]);
		assertNotNull("1.1", deltasMap.get(new Long(1)));
		assertNotNull("1.2", deltasMap.get(new Long(2)));
		assertNotNull("1.3", deltasMap.get(new Long(3)));
		assertEquals("2.1", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(1))).getType());
		assertEquals("2.2", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(2))).getType());
		assertEquals("2.3", (BundleDelta.RESOLVED | BundleDelta.ADDED), ((BundleDelta) deltasMap.get(new Long(3))).getType());
		delta = state.resolve(new BundleDescription[] {state.getBundle(1)});
		deltas = delta.getChanges();
		assertEquals("3.0", 3, deltas.length);
		deltasMap = new HashMap();
		for (int i = 0; i < deltas.length; i++)
			deltasMap.put(new Long(deltas[i].getBundle().getBundleId()), deltas[i]);
		assertNotNull("3.1", deltasMap.get(new Long(1)));
		assertNotNull("3.2", deltasMap.get(new Long(2)));
		assertNotNull("3.3", deltasMap.get(new Long(3)));
		assertEquals("3.4", BundleDelta.RESOLVED, ((BundleDelta) deltasMap.get(new Long(1))).getType());
		assertEquals("3.5", BundleDelta.RESOLVED, ((BundleDelta) deltasMap.get(new Long(2))).getType());
		assertEquals("3.6", BundleDelta.RESOLVED, ((BundleDelta) deltasMap.get(new Long(3))).getType());

	}

	public void testUpdate() throws BundleException {
		State state = buildEmptyState();
		String B1_LOCATION = "org.eclipse.b";
		final String B1_RESOLVED = "Bundle-SymbolicName: org.eclipse.b1\n" + "Bundle-Version: 1.0\n";
		final String B1_UNRESOLVED = "Bundle-SymbolicName: org.eclipse.b1\n" + "Bundle-Version: 2.0\nRequire-Bundle: non.existant.bundle\n";
		BundleDescription b1 = state.getFactory().createBundleDescription(parseManifest(B1_RESOLVED), B1_LOCATION, 1);
		assertTrue("0.9", state.addBundle(b1));
		StateDelta delta = state.resolve();
		b1 = state.getBundleByLocation(b1.getLocation());
		BundleDelta[] changes = delta.getChanges();
		assertEquals("1.0", 1, changes.length);
		assertEquals("1.1", b1, changes[0].getBundle());
		assertEquals("1.2", (BundleDelta.ADDED | BundleDelta.RESOLVED), changes[0].getType());
		assertFullyResolved("1.3", b1);
		assertTrue("1.8", contains(state.getResolvedBundles(), b1));
		BundleDescription b11 = state.getFactory().createBundleDescription(parseManifest(B1_UNRESOLVED), B1_LOCATION, 1);
		assertTrue("1.8b", state.updateBundle(b11));
		b11 = state.getBundle(b11.getBundleId());
		assertTrue("1.9", !contains(state.getResolvedBundles(), b11));
		delta = state.resolve();
		changes = delta.getChanges();
		assertEquals("2.0", 2, changes.length);
		HashMap deltasMap = new HashMap();
		for (int i = 0; i < changes.length; i++)
			deltasMap.put(changes[i].getBundle(), changes[i]);
		assertNotNull("2.1", deltasMap.get(b1));
		assertNotNull("2.2", deltasMap.get(b11));
		assertEquals("2.3", BundleDelta.UNRESOLVED, ((BundleDelta) deltasMap.get(b1)).getType());
		assertEquals("2.4", BundleDelta.UPDATED, ((BundleDelta) deltasMap.get(b11)).getType());
		BundleDescription b111 = state.getFactory().createBundleDescription(parseManifest(B1_RESOLVED), B1_LOCATION, 1);
		assertTrue("3.0", state.updateBundle(b111));
		b111 = state.getBundle(b111.getBundleId());
		assertTrue("3.1", !contains(state.getResolvedBundles(), b111));
		delta = state.resolve();
		changes = delta.getChanges();
		assertEquals("3.2", 1, changes.length);
		assertEquals("3.1", b111, changes[0].getBundle());
		assertEquals("3.2", BundleDelta.UPDATED | BundleDelta.RESOLVED, changes[0].getType());
		assertFullyResolved("3.3", b111);
	}

	public void testSingletons() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost100 = state.getFactory().createBundleDescription(state, manifest, "test.host100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testHost101 = state.getFactory().createBundleDescription(state, manifest, "test.host101", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag100 = state.getFactory().createBundleDescription(state, manifest, "test.frag100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testFrag101 = state.getFactory().createBundleDescription(state, manifest, "test.frag101", 3);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.dependent; singleton:=true");
		manifest.put(Constants.REQUIRE_BUNDLE, "test.host; bundle-version=\"[1.0.1,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testDependent = state.getFactory().createBundleDescription(state, manifest, "test.frag101", 4);

		state.addBundle(testHost100);
		state.addBundle(testFrag100);
		state.addBundle(testHost101);
		state.addBundle(testFrag101);
		state.addBundle(testDependent);
		state.resolve();
		assertFalse("1.0", testHost100.isResolved());
		assertTrue("1.1", testHost101.isResolved());
		assertFalse("1.2", testFrag100.isResolved());
		assertTrue("1.3", testFrag101.isResolved());
		assertTrue("1.4", testDependent.isResolved());
	}

	public void testSingletonsSameVersion() throws BundleException {
		// this is a testcase to handle how PDE build is using the state
		// with multiple singleton bundles installed with the same BSN and version
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost100 = state.getFactory().createBundleDescription(state, manifest, "test.host100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost101 = state.getFactory().createBundleDescription(state, manifest, "test.host101", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag100 = state.getFactory().createBundleDescription(state, manifest, "test.frag100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag101 = state.getFactory().createBundleDescription(state, manifest, "test.frag101", 3);

		state.addBundle(testHost100);
		state.addBundle(testFrag100);
		state.resolve();
		state.addBundle(testHost101);
		state.addBundle(testFrag101);
		state.resolve();
		assertTrue("1.0", testHost100.isResolved());
		assertFalse("1.1", testHost101.isResolved());
		assertTrue("1.2", testFrag100.isResolved());
		assertFalse("1.3", testFrag101.isResolved());
	}

	public void testSingletonsSelection1() throws BundleException {
		State state = buildEmptyState();

		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\"");
		BundleDescription sdk10 = state.getFactory().createBundleDescription(state, manifest, "sdk10", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform10 = state.getFactory().createBundleDescription(state, manifest, "platform10", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription rcp10 = state.getFactory().createBundleDescription(state, manifest, "rcp10", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "gef; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,1.0]\"");
		BundleDescription gef10 = state.getFactory().createBundleDescription(state, manifest, "gef10", 3);

		state.addBundle(sdk10);
		state.addBundle(platform10);
		state.addBundle(rcp10);
		state.addBundle(gef10);
		state.resolve();

		assertTrue("1.0", sdk10.isResolved());
		assertTrue("1.1", platform10.isResolved());
		assertTrue("1.2", rcp10.isResolved());
		assertTrue("1.3", gef10.isResolved());

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\"");
		BundleDescription sdk20 = state.getFactory().createBundleDescription(state, manifest, "sdk20", 4);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform20 = state.getFactory().createBundleDescription(state, manifest, "platform20", 5);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		BundleDescription rcp20 = state.getFactory().createBundleDescription(state, manifest, "rcp20", 6);

		state.addBundle(sdk20);
		state.addBundle(platform20);
		state.addBundle(rcp20);
		state.resolve(false);

		assertTrue("2.0", sdk20.isResolved());
		assertTrue("2.1", platform20.isResolved());
		assertTrue("2.2", rcp10.isResolved());
		assertTrue("2.3", gef10.isResolved());
		assertFalse("2.4", sdk10.isResolved());
		assertFalse("2.5", platform10.isResolved());
		assertFalse("2.6", rcp20.isResolved());
	}

	public void testSingletonsSelection2() throws BundleException {
		State state = buildEmptyState();

		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; test with cycle added
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\", cycle");
		BundleDescription sdk10 = state.getFactory().createBundleDescription(state, manifest, "sdk10", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform10 = state.getFactory().createBundleDescription(state, manifest, "platform10", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription rcp10 = state.getFactory().createBundleDescription(state, manifest, "rcp10", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "gef; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,1.0]\", sdk");
		BundleDescription gef10 = state.getFactory().createBundleDescription(state, manifest, "gef10", 3);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "cycle; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "gef");
		BundleDescription cycle10 = state.getFactory().createBundleDescription(state, manifest, "gef10", 4);


		state.addBundle(sdk10);
		state.addBundle(platform10);
		state.addBundle(rcp10);
		state.addBundle(gef10);
		state.addBundle(cycle10);
		state.resolve();

		assertTrue("1.0", sdk10.isResolved());
		assertTrue("1.1", platform10.isResolved());
		assertTrue("1.2", rcp10.isResolved());
		assertTrue("1.3", gef10.isResolved());
		assertTrue("1.4", cycle10.isResolved());

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\"");
		BundleDescription sdk20 = state.getFactory().createBundleDescription(state, manifest, "sdk20", 5);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform20 = state.getFactory().createBundleDescription(state, manifest, "platform20", 6);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		BundleDescription rcp20 = state.getFactory().createBundleDescription(state, manifest, "rcp20", 7);

		state.addBundle(sdk20);
		state.addBundle(platform20);
		state.addBundle(rcp20);
		state.resolve(false);

		assertTrue("2.0", sdk20.isResolved());
		assertTrue("2.1", platform20.isResolved());
		assertTrue("2.2", rcp10.isResolved());
		assertTrue("2.3", gef10.isResolved());
		assertTrue("2.4", cycle10.isResolved());
		assertFalse("2.5", sdk10.isResolved());
		assertFalse("2.6", platform10.isResolved());
		assertFalse("2.7", rcp20.isResolved());
	}

	public void testSingletonsSelection3() throws BundleException {
		State state = buildEmptyState();
		int bundleID = 0;
		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; with fragments
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\"");
		BundleDescription sdk10 = state.getFactory().createBundleDescription(state, manifest, "sdk10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "sdk");
		BundleDescription sdk_frag10 = state.getFactory().createBundleDescription(state, manifest, "sdk.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "sdk; bundle-version=2.0");
		BundleDescription sdk_frag210 = state.getFactory().createBundleDescription(state, manifest, "sdk.frag210", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform10 = state.getFactory().createBundleDescription(state, manifest, "platform10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "platform");
		BundleDescription platform_frag10 = state.getFactory().createBundleDescription(state, manifest, "platform.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "platform; bundle-version=2.0");
		BundleDescription platform_frag210 = state.getFactory().createBundleDescription(state, manifest, "platform.frag210", bundleID++);


		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription rcp10 = state.getFactory().createBundleDescription(state, manifest, "rcp10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "rcp");
		BundleDescription rcp_frag10 = state.getFactory().createBundleDescription(state, manifest, "rcp.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "rcp; bundle-version=2.0");
		BundleDescription rcp_frag210 = state.getFactory().createBundleDescription(state, manifest, "rcp.frag210", bundleID++);


		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "gef; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,1.0]\"");
		BundleDescription gef10 = state.getFactory().createBundleDescription(state, manifest, "gef10", bundleID++);

		state.addBundle(sdk10);
		state.addBundle(sdk_frag10);
		state.addBundle(sdk_frag210);
		state.addBundle(platform10);
		state.addBundle(platform_frag10);
		state.addBundle(platform_frag210);
		state.addBundle(rcp10);
		state.addBundle(rcp_frag10);
		state.addBundle(rcp_frag210);
		state.addBundle(gef10);
		state.resolve();

		assertTrue("1.0", sdk10.isResolved());
		assertTrue("1.0.1", sdk_frag10.isResolved());
		assertFalse("1.0.2", sdk_frag210.isResolved());
		assertTrue("1.1", platform10.isResolved());
		assertTrue("1.1.1", platform_frag10.isResolved());
		assertFalse("1.1.2", platform_frag210.isResolved());
		assertTrue("1.2", rcp10.isResolved());
		assertTrue("1.2.1", rcp_frag10.isResolved());
		assertFalse("1.2.2", rcp_frag210.isResolved());
		assertTrue("1.3", gef10.isResolved());

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "platform; bundle-version=\"[1.0,2.0]\"");
		BundleDescription sdk20 = state.getFactory().createBundleDescription(state, manifest, "sdk20", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "rcp; bundle-version=\"[1.0,2.0]\"");
		BundleDescription platform20 = state.getFactory().createBundleDescription(state, manifest, "platform20", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		BundleDescription rcp20 = state.getFactory().createBundleDescription(state, manifest, "rcp20", bundleID++);

		state.addBundle(sdk20);
		state.addBundle(platform20);
		state.addBundle(rcp20);
		state.resolve(false);

		assertTrue("2.0", sdk20.isResolved());
		assertTrue("2.0.1", sdk_frag10.isResolved());
		assertTrue("2.0.2", sdk_frag210.isResolved());
		assertTrue("2.1", platform20.isResolved());
		assertTrue("2.1.1", platform_frag10.isResolved());
		assertTrue("2.1.2", platform_frag210.isResolved());
		assertTrue("2.2", rcp10.isResolved());
		assertTrue("2.2.1", rcp_frag10.isResolved());
		assertTrue("2.3", gef10.isResolved());
		assertFalse("2.4", sdk10.isResolved());
		assertFalse("2.5", platform10.isResolved());
		assertFalse("2.6", rcp20.isResolved());
		assertFalse("2.2.2", rcp_frag210.isResolved());
	}

	public void testSingletonsSelection4() throws BundleException {
		State state = buildEmptyState();
		int bundleID = 0;
		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; with fragments using Import-Package
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "sdk; version=1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "platform; version=\"[1.0,2.0]\"");
		BundleDescription sdk10 = state.getFactory().createBundleDescription(state, manifest, "sdk10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "sdk");
		BundleDescription sdk_frag10 = state.getFactory().createBundleDescription(state, manifest, "sdk.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "sdk; bundle-version=2.0");
		BundleDescription sdk_frag210 = state.getFactory().createBundleDescription(state, manifest, "sdk.frag210", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "platform; version=1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "rcp; version=\"[1.0,2.0]\"");
		BundleDescription platform10 = state.getFactory().createBundleDescription(state, manifest, "platform10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "platform");
		BundleDescription platform_frag10 = state.getFactory().createBundleDescription(state, manifest, "platform.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "platform; bundle-version=2.0");
		BundleDescription platform_frag210 = state.getFactory().createBundleDescription(state, manifest, "platform.frag210", bundleID++);


		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "rcp; version=1.0");
		BundleDescription rcp10 = state.getFactory().createBundleDescription(state, manifest, "rcp10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp.frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "rcp");
		BundleDescription rcp_frag10 = state.getFactory().createBundleDescription(state, manifest, "rcp.frag10", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp.frag2; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "rcp; bundle-version=2.0");
		BundleDescription rcp_frag210 = state.getFactory().createBundleDescription(state, manifest, "rcp.frag210", bundleID++);


		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "gef; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "rcp; version=\"[1.0,1.0]\"");
		BundleDescription gef10 = state.getFactory().createBundleDescription(state, manifest, "gef10", bundleID++);

		state.addBundle(sdk10);
		state.addBundle(sdk_frag10);
		state.addBundle(sdk_frag210);
		state.addBundle(platform10);
		state.addBundle(platform_frag10);
		state.addBundle(platform_frag210);
		state.addBundle(rcp10);
		state.addBundle(rcp_frag10);
		state.addBundle(rcp_frag210);
		state.addBundle(gef10);
		state.resolve();

		assertTrue("1.0", sdk10.isResolved());
		assertTrue("1.0.1", sdk_frag10.isResolved());
		assertFalse("1.0.2", sdk_frag210.isResolved());
		assertTrue("1.1", platform10.isResolved());
		assertTrue("1.1.1", platform_frag10.isResolved());
		assertFalse("1.1.2", platform_frag210.isResolved());
		assertTrue("1.2", rcp10.isResolved());
		assertTrue("1.2.1", rcp_frag10.isResolved());
		assertFalse("1.2.2", rcp_frag210.isResolved());
		assertTrue("1.3", gef10.isResolved());

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "sdk; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.EXPORT_PACKAGE, "sdk; version=2.0");
		manifest.put(Constants.IMPORT_PACKAGE, "platform; version=\"[1.0,2.0]\"");
		BundleDescription sdk20 = state.getFactory().createBundleDescription(state, manifest, "sdk20", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "platform; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.EXPORT_PACKAGE, "platform; version=2.0");
		manifest.put(Constants.IMPORT_PACKAGE, "rcp; version=\"[1.0,2.0]\"");
		BundleDescription platform20 = state.getFactory().createBundleDescription(state, manifest, "platform20", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "rcp; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.EXPORT_PACKAGE, "rcp; version=2.0");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		BundleDescription rcp20 = state.getFactory().createBundleDescription(state, manifest, "rcp20", bundleID++);

		state.addBundle(sdk20);
		state.addBundle(platform20);
		state.addBundle(rcp20);
		state.resolve(false);

		assertTrue("2.0", sdk20.isResolved());
		assertTrue("2.0.1", sdk_frag10.isResolved());
		assertTrue("2.0.2", sdk_frag210.isResolved());
		assertTrue("2.1", platform20.isResolved());
		assertTrue("2.1.1", platform_frag10.isResolved());
		assertTrue("2.1.2", platform_frag210.isResolved());
		assertTrue("2.2", rcp10.isResolved());
		assertTrue("2.2.1", rcp_frag10.isResolved());
		assertTrue("2.3", gef10.isResolved());
		assertFalse("2.4", sdk10.isResolved());
		assertFalse("2.5", platform10.isResolved());
		assertFalse("2.6", rcp20.isResolved());
		assertFalse("2.2.2", rcp_frag210.isResolved());
	}

	public void testSingletonsSelection5() throws BundleException {
		State state = buildEmptyState();

		long id = 0;
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "base; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription base10 = state.getFactory().createBundleDescription(state, manifest, "base10", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "base; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.1");
		manifest.put(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "J2SE-1.6");
		BundleDescription base11 = state.getFactory().createBundleDescription(state, manifest, "base11", id++);
	
		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "requires; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "base; bundle-version=\"[1.0,1.1)\"");
		BundleDescription requires10 = state.getFactory().createBundleDescription(state, manifest, "requires10", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "requires; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.1");
		manifest.put(Constants.REQUIRE_BUNDLE, "base; bundle-version=\"[1.1,1.2)\"");
		BundleDescription requires11 = state.getFactory().createBundleDescription(state, manifest, "requires11", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "requires; bundle-version=\"[1.0,1.1)\"");
		BundleDescription frag10 = state.getFactory().createBundleDescription(state, manifest, "frag10", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "frag; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.1");
		manifest.put(Constants.FRAGMENT_HOST, "requires; bundle-version=\"[1.1,1.2)\"");
		BundleDescription frag11 = state.getFactory().createBundleDescription(state, manifest, "frag11", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "fragb; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "requires; bundle-version=\"[1.0,1.1)\"");
		manifest.put(Constants.EXPORT_PACKAGE, "fragb; version=1.0");
		BundleDescription fragb10 = state.getFactory().createBundleDescription(state, manifest, "frag10", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "fragb; " + Constants.SINGLETON_DIRECTIVE + ":=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.1");
		manifest.put(Constants.FRAGMENT_HOST, "requires; bundle-version=\"[1.0,1.1)\"");
		manifest.put(Constants.EXPORT_PACKAGE, "fragb; version=1.1");
		BundleDescription fragb11 = state.getFactory().createBundleDescription(state, manifest, "frag11", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "import");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "fragb; version=\"[1.0,1.0]\"");
		BundleDescription import10 = state.getFactory().createBundleDescription(state, manifest, "import10", id++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "import");
		manifest.put(Constants.BUNDLE_VERSION, "1.1");
		manifest.put(Constants.IMPORT_PACKAGE, "fragb; version=\"[1.1,1.1]\"");
		BundleDescription import11 = state.getFactory().createBundleDescription(state, manifest, "import11", id++);

		state.addBundle(base10);
		state.addBundle(base11);
		state.addBundle(requires10);
		state.addBundle(requires11);
		state.addBundle(frag10);
		state.addBundle(frag11);
		state.addBundle(fragb10);
		state.addBundle(fragb11);
		state.addBundle(import10);
		state.addBundle(import11);
		state.resolve();

		assertTrue("1.0", base10.isResolved());
		assertTrue("1.1", requires10.isResolved());
		assertTrue("1.2", frag10.isResolved());
		assertTrue("1.3", fragb11.isResolved());
		assertTrue("1.4", import11.isResolved());
		assertFalse("1.5", base11.isResolved());
		assertFalse("1.6", requires11.isResolved());
		assertFalse("1.7", frag11.isResolved());
		assertFalse("1.8", fragb10.isResolved());
		assertFalse("1.9", import10.isResolved());
	}

	public void testNonSingletonsSameVersion() throws BundleException {
		// this is a testcase to handle how PDE build is using the state
		// with multiple singleton bundles installed with the same BSN and version
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost100 = state.getFactory().createBundleDescription(state, manifest, "test.host100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost101 = state.getFactory().createBundleDescription(state, manifest, "test.host101", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag100 = state.getFactory().createBundleDescription(state, manifest, "test.frag100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag101 = state.getFactory().createBundleDescription(state, manifest, "test.frag101", 3);

		state.addBundle(testHost100);
		state.addBundle(testFrag100);
		state.resolve();
		state.addBundle(testHost101);
		state.addBundle(testFrag101);
		state.resolve();
		assertTrue("1.0", testHost100.isResolved());
		assertTrue("1.1", testHost101.isResolved());
		assertTrue("1.2", testFrag100.isResolved());
		assertTrue("1.3", testFrag101.isResolved());
	}

	public void testTransitiveUses() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		BundleDescription a1_100 = state.getFactory().createBundleDescription(state, manifest, "a1_100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		BundleDescription a2_100 = state.getFactory().createBundleDescription(state, manifest, "a2_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A2");
		BundleDescription b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c; uses:=b");
		manifest.put(Constants.IMPORT_PACKAGE, "b");
		BundleDescription c1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", 3);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "D1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.IMPORT_PACKAGE, "a, c");
		BundleDescription d1_100 = state.getFactory().createBundleDescription(state, manifest, "d1_100", 4);

		state.addBundle(a1_100);
		state.addBundle(a2_100);
		state.addBundle(b1_100);
		state.addBundle(c1_100);
		state.addBundle(d1_100);
		state.resolve();

		ExportPackageDescription[] b1ResolvedImports = b1_100.getResolvedImports();
		ExportPackageDescription[] d1ResolvedImports = d1_100.getResolvedImports();
		ExportPackageDescription[] isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("1.1 Packages are not consistent: " + isConsistent, isConsistent);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A1");
		b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 2);
		state.updateBundle(b1_100);
		state.resolve();

		b1ResolvedImports = b1_100.getResolvedImports();
		d1ResolvedImports = d1_100.getResolvedImports();
		isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("1.2 Packages are not consistent: " + isConsistent, isConsistent);
	}

	public void testRequireBundleUses() throws BundleException {
		State state = buildEmptyState();
		int id = 0;
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		BundleDescription a_100 = state.getFactory().createBundleDescription(state, manifest, "a_100", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "2.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		BundleDescription a_200 = state.getFactory().createBundleDescription(state, manifest, "a_200", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.REQUIRE_BUNDLE, "A; visibility:=reexport");
		BundleDescription b_100 = state.getFactory().createBundleDescription(state, manifest, "b_100", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c; uses:=b");
		manifest.put(Constants.REQUIRE_BUNDLE, "A, B");
		BundleDescription c_100 = state.getFactory().createBundleDescription(state, manifest, "c_100", id++);

		state.addBundle(a_100);
		state.addBundle(b_100);
		// first resolve just A and B
		state.resolve();
		assertTrue("0.1", a_100.isResolved());
		assertTrue("0.2", b_100.isResolved());
		// now add A v2 and resolve it
		state.addBundle(a_200);
		state.resolve();
		assertTrue("1.1", a_200.isResolved());
		// now add C and make sure it does not get packages from A v2
		state.addBundle(c_100);
		state.resolve();
		assertTrue("1.2", c_100.isResolved());

		ExportPackageDescription[] c1ResolvedImports = state.getStateHelper().getVisiblePackages(c_100);
		assertTrue("2.1", c1ResolvedImports.length == 2);
		int index = c1ResolvedImports[0].getName().equals("a") ? 0 : c1ResolvedImports[1].getName().equals("a") ? 1 : -1;
		assertTrue("2.2", index >= 0);
		assertEquals("2.2", c1ResolvedImports[index].getExporter(), a_100);
	}

	public void testCyclicTransitiveUses() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; uses:=d");
		manifest.put(Constants.IMPORT_PACKAGE, "d");
		BundleDescription a1_100 = state.getFactory().createBundleDescription(state, manifest, "a1_100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; uses:=d");
		manifest.put(Constants.IMPORT_PACKAGE, "d");
		BundleDescription a2_100 = state.getFactory().createBundleDescription(state, manifest, "a2_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A2");
		BundleDescription b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c; uses:=b");
		manifest.put(Constants.IMPORT_PACKAGE, "b");
		BundleDescription c1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", 3);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "D1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "d; uses:=c");
		manifest.put(Constants.IMPORT_PACKAGE, "a, c");
		BundleDescription d1_100 = state.getFactory().createBundleDescription(state, manifest, "d1_100", 4);

		state.addBundle(a1_100);
		state.addBundle(a2_100);
		state.addBundle(b1_100);
		state.addBundle(c1_100);
		state.addBundle(d1_100);
		state.resolve();

		assertFalse("0.1", a1_100.isResolved());
		assertTrue("0.2", a2_100.isResolved());
		assertTrue("0.3", b1_100.isResolved());
		assertTrue("0.4", c1_100.isResolved());
		assertTrue("0.5", d1_100.isResolved());

		ExportPackageDescription[] b1ResolvedImports = b1_100.getResolvedImports();
		ExportPackageDescription[] d1ResolvedImports = d1_100.getResolvedImports();
		ExportPackageDescription[] isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("1.1 Packages are not consistent: " + isConsistent, isConsistent);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A1");
		b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 2);
		state.updateBundle(b1_100);
		state.resolve();

		assertTrue("2.1", a1_100.isResolved());
		assertFalse("2.2", a2_100.isResolved());
		assertTrue("2.3", b1_100.isResolved());
		assertTrue("2.4", c1_100.isResolved());
		assertTrue("2.5", d1_100.isResolved());
		
		b1ResolvedImports = b1_100.getResolvedImports();
		d1ResolvedImports = d1_100.getResolvedImports();
		isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("3.1 Packages are not consistent: " + isConsistent, isConsistent);
	}

	public void testFragmentTransitiveUses() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; uses:=d");
		manifest.put(Constants.IMPORT_PACKAGE, "d");
		BundleDescription a1_100 = state.getFactory().createBundleDescription(state, manifest, "a1_100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1.Frag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.FRAGMENT_HOST, "A1");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag; uses:=a");
		BundleDescription a1frag_100 = state.getFactory().createBundleDescription(state, manifest, "a1frag_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A2");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; uses:=d, a.frag; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "d");
		BundleDescription a2_100 = state.getFactory().createBundleDescription(state, manifest, "a2_100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A2, a.frag");
		BundleDescription b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 3);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c; uses:=b");
		manifest.put(Constants.IMPORT_PACKAGE, "b");
		BundleDescription c1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", 4);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "D1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "d; uses:=c");
		manifest.put(Constants.IMPORT_PACKAGE, "a.frag, c");
		BundleDescription d1_100 = state.getFactory().createBundleDescription(state, manifest, "d1_100", 5);

		state.addBundle(a1_100);
		state.addBundle(a1frag_100);
		state.addBundle(a2_100);
		state.addBundle(b1_100);
		state.addBundle(c1_100);
		state.addBundle(d1_100);
		state.resolve();

		assertFalse("0.1", a1_100.isResolved());
		assertTrue("0.2", a2_100.isResolved());
		assertTrue("0.3", b1_100.isResolved());
		assertTrue("0.4", c1_100.isResolved());
		assertTrue("0.5", d1_100.isResolved());

		ExportPackageDescription[] b1ResolvedImports = b1_100.getResolvedImports();
		ExportPackageDescription[] d1ResolvedImports = d1_100.getResolvedImports();
		ExportPackageDescription[] isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("1.1 Packages are not consistent: " + isConsistent, isConsistent);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; uses:=a");
		manifest.put(Constants.IMPORT_PACKAGE, "a;bundle-symbolic-name=A1");
		b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 2);
		state.updateBundle(b1_100);
		state.resolve();

		assertTrue("2.1", a1_100.isResolved());
		assertFalse("2.2", a2_100.isResolved());
		assertTrue("2.3", b1_100.isResolved());
		assertTrue("2.4", c1_100.isResolved());
		assertTrue("2.5", d1_100.isResolved());

		b1ResolvedImports = b1_100.getResolvedImports();
		d1ResolvedImports = d1_100.getResolvedImports();
		isConsistent = isConsistent(b1ResolvedImports, d1ResolvedImports);
		assertNull("3.1 Packages are not consistent: " + isConsistent, isConsistent);
	}

	public void testCyclicUsesExportDrop() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "W");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; b; version=1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "c, a, b");
		BundleDescription w1_100 = state.getFactory().createBundleDescription(state, manifest, "w1_100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "X");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c");
		manifest.put(Constants.IMPORT_PACKAGE, "d");
		BundleDescription x1_100 = state.getFactory().createBundleDescription(state, manifest, "x1_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "Y");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "d");
		manifest.put(Constants.IMPORT_PACKAGE, "a");
		BundleDescription y1_100 = state.getFactory().createBundleDescription(state, manifest, "y1_100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "Z");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; version = 2.0");
		BundleDescription z1_100 = state.getFactory().createBundleDescription(state, manifest, "z1_100", 3);

		state.addBundle(w1_100);
		state.addBundle(x1_100);
		state.addBundle(y1_100);
		state.addBundle(z1_100);
		state.resolve();

		assertTrue("0.1", w1_100.isResolved());
		assertTrue("0.2", x1_100.isResolved());
		assertTrue("0.3", y1_100.isResolved());
		assertTrue("0.4", z1_100.isResolved());

		assertEquals("1.1", 1, w1_100.getSelectedExports().length);
		assertEquals("1.2", "b", w1_100.getSelectedExports()[0].getName());
	}

	public void testRemovalPending() throws BundleException {
		State state = buildEmptyState();
		Hashtable wManifest = new Hashtable();
		wManifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		wManifest.put(Constants.BUNDLE_SYMBOLICNAME, "W");
		wManifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		wManifest.put(Constants.EXPORT_PACKAGE, "a; b; version=1.0");
		wManifest.put(Constants.IMPORT_PACKAGE, "a, b");
		BundleDescription w1_100 = state.getFactory().createBundleDescription(state, wManifest, "w1_100", 0);

		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "X");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.IMPORT_PACKAGE, "a");
		BundleDescription x1_100 = state.getFactory().createBundleDescription(state, manifest, "x1_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "Y");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.IMPORT_PACKAGE, "a");
		BundleDescription y1_100 = state.getFactory().createBundleDescription(state, manifest, "y1_100", 2);

		state.addBundle(w1_100);
		state.addBundle(x1_100);
		state.addBundle(y1_100);
		
		state.resolve();

		assertTrue("0.1", w1_100.isResolved());
		assertTrue("0.2", x1_100.isResolved());
		assertTrue("0.3", y1_100.isResolved());

		BundleDescription w1_100_prime = state.getFactory().createBundleDescription(state, wManifest, "w1_100", 0);
		state.updateBundle(w1_100_prime);
		state.resolve(new BundleDescription[0]);

		assertTrue("1.1", w1_100_prime.isResolved());
		assertTrue("1.2", x1_100.isResolved());
		assertTrue("1.3", y1_100.isResolved());

		ExportPackageDescription[] exports_w1_100 = w1_100.getSelectedExports();
		ExportPackageDescription[] imports_w1_100_prime = w1_100_prime.getResolvedImports();
		ExportPackageDescription[] isConsistent = isConsistent(exports_w1_100, imports_w1_100_prime);
		assertNull("2.1 Packages are not consistent: " + isConsistent, isConsistent);

		state.resolve(new BundleDescription[] {w1_100});
		assertTrue("3.1", w1_100_prime.isResolved());
		assertTrue("3.2", x1_100.isResolved());
		assertTrue("3.3", y1_100.isResolved());

		ExportPackageDescription[] exports_w1_100_prime = w1_100_prime.getSelectedExports();
		imports_w1_100_prime = w1_100_prime.getResolvedImports();
		isConsistent = isConsistent(exports_w1_100_prime, imports_w1_100_prime);
		assertNull("4.1 Packages are not consistent: " + isConsistent, isConsistent);
	}

	public void testFragmentConstraints() throws BundleException {
		int id = 0;
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "D1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.IMPORT_PACKAGE, "a.frag");
		BundleDescription d1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", id++);

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a; uses:=d");
		manifest.put(Constants.IMPORT_PACKAGE, "b; version=2.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "C1; bundle-version=\"[2.0, 3.0)\"");
		BundleDescription a1_100 = state.getFactory().createBundleDescription(state, manifest, "a1_100", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1.Frag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.FRAGMENT_HOST, "A1");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag");
		manifest.put(Constants.IMPORT_PACKAGE, "b; version=1.0");
		manifest.put(Constants.REQUIRE_BUNDLE, "C1; bundle-version=\"[2.0, 3.0)\"");
		BundleDescription a1frag_100 = state.getFactory().createBundleDescription(state, manifest, "a1frag_100", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b; version=2.1");
		BundleDescription b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", id++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C1");
		manifest.put(Constants.BUNDLE_VERSION, "2.0.1");
		manifest.put(Constants.EXPORT_PACKAGE, "c");
		BundleDescription c1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", id++);

		state.addBundle(d1_100);
		state.addBundle(a1_100);
		state.addBundle(a1frag_100);
		state.addBundle(b1_100);
		state.addBundle(c1_100);
		state.resolve();

		assertTrue("0.1", a1_100.isResolved());
		assertTrue("0.2", a1frag_100.isResolved());
		assertTrue("0.3", b1_100.isResolved());
		assertTrue("0.4", c1_100.isResolved());
		assertTrue("0.5", d1_100.isResolved());

		// now use a fragment that has conflicting imports/requires with the host
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1.Frag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.FRAGMENT_HOST, "A1");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag");
		manifest.put(Constants.IMPORT_PACKAGE, "b; version=2.1");
		manifest.put(Constants.REQUIRE_BUNDLE, "C1; bundle-version=\"[2.5, 4.0)\"");
		a1frag_100 = state.getFactory().createBundleDescription(state, manifest, "a1frag_100", a1frag_100.getBundleId());
		state.updateBundle(a1frag_100);
		state.resolve(new BundleDescription[] {a1frag_100});

		assertTrue("1.1", a1_100.isResolved());
		assertFalse("1.2", a1frag_100.isResolved());
		assertTrue("1.3", b1_100.isResolved());
		assertTrue("1.4", c1_100.isResolved());
		assertFalse("1.5", d1_100.isResolved());
		
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1.Frag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.FRAGMENT_HOST, "A1");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag");
		manifest.put(Constants.IMPORT_PACKAGE, "b; version=\"[1.0,2.0)\"");
		a1frag_100 = state.getFactory().createBundleDescription(state, manifest, "a1frag_100", a1frag_100.getBundleId());
		state.updateBundle(a1frag_100);
		state.resolve(new BundleDescription[] {a1frag_100});

		assertTrue("2.1", a1_100.isResolved());
		assertFalse("2.2", a1frag_100.isResolved());
		assertTrue("2.3", b1_100.isResolved());
		assertTrue("2.4", c1_100.isResolved());
		assertFalse("2.5", d1_100.isResolved());

		// now use a fragment that has conflicting imports/requires with the host
		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1.Frag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.FRAGMENT_HOST, "A1");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag");
		manifest.put(Constants.REQUIRE_BUNDLE, "C1; bundle-version=\"[1.0, 1.5)\"");
		a1frag_100 = state.getFactory().createBundleDescription(state, manifest, "a1frag_100", a1frag_100.getBundleId());
		state.updateBundle(a1frag_100);
		state.resolve(new BundleDescription[] {a1frag_100});

		assertTrue("3.1", a1_100.isResolved());
		assertFalse("3.2", a1frag_100.isResolved());
		assertTrue("3.3", b1_100.isResolved());
		assertTrue("3.4", c1_100.isResolved());
		assertFalse("3.5", d1_100.isResolved());
	}

	public void testFragmentsBug188199() throws BundleException {
		State state = buildEmptyState();
		int bundleID = 0;
		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; with fragments using Import-Package
		Hashtable manifest = new Hashtable();

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		manifest.put(Constants.IMPORT_PACKAGE, "c");
		BundleDescription a = state.getFactory().createBundleDescription(state, manifest, "A", bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "AFrag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "A");
		manifest.put(Constants.EXPORT_PACKAGE, "a.frag");
		BundleDescription aFrag = state.getFactory().createBundleDescription(state, manifest, "A", bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "a, a.frag");
		BundleDescription b = state.getFactory().createBundleDescription(state, manifest, "B", bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c");
		BundleDescription c = state.getFactory().createBundleDescription(state, manifest, "C", bundleID++);

		state.addBundle(a);
		state.addBundle(aFrag);
		state.addBundle(b);
		state.addBundle(c);
		state.resolve();
		assertTrue("0.1", a.isResolved());
		assertTrue("0.2", aFrag.isResolved());
		assertTrue("0.3", b.isResolved());
		assertTrue("0.4", c.isResolved());

		state.removeBundle(c);
		state.resolve(false);
		assertFalse("1.1", a.isResolved());
		assertFalse("1.2", aFrag.isResolved());
		assertFalse("1.3", b.isResolved());

		state.addBundle(c);
		state.resolve();
		assertTrue("2.1", a.isResolved());
		assertTrue("2.2", aFrag.isResolved());
		assertTrue("2.3", b.isResolved());
		assertTrue("2.4", c.isResolved());

		ExportPackageDescription[] aExports = a.getSelectedExports();
		ExportPackageDescription[] bImports = b.getResolvedImports();
		assertTrue("3.1", aExports.length == 2);
		assertTrue("3.2", bImports.length == 2);
		assertTrue("3.3", aExports[0] == bImports[0]);
		assertTrue("3.4", aExports[1] == bImports[1]);
	}

	public void testFragmentsMultipleVersion() throws BundleException {
		State state = buildEmptyState();
		int bundleID = 0;
		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; with fragments using Import-Package
		Hashtable manifest = new Hashtable();

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription a1 = state.getFactory().createBundleDescription(state, manifest, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME) + '_' + (String) manifest.get(Constants.BUNDLE_VERSION), bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "AFrag");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.FRAGMENT_HOST, "A");
		BundleDescription aFrag1 = state.getFactory().createBundleDescription(state, manifest, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME) + '_' + (String) manifest.get(Constants.BUNDLE_VERSION), bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		BundleDescription a2 = state.getFactory().createBundleDescription(state, manifest, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME) + '_' + (String) manifest.get(Constants.BUNDLE_VERSION), bundleID++);

		manifest.clear();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "AFrag");
		manifest.put(Constants.BUNDLE_VERSION, "2.0");
		manifest.put(Constants.FRAGMENT_HOST, "A");
		BundleDescription aFrag2 = state.getFactory().createBundleDescription(state, manifest, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME) + '_' + (String) manifest.get(Constants.BUNDLE_VERSION), bundleID++);

		state.addBundle(a1);
		state.addBundle(aFrag1);
		state.addBundle(a2);
		state.addBundle(aFrag2);
		state.resolve();
		assertTrue("0.1", a1.isResolved());
		assertTrue("0.2", aFrag1.isResolved());
		assertTrue("0.3", a2.isResolved());
		assertTrue("0.4", aFrag2.isResolved());

		state.removeBundle(a2);
		state.resolve(false);
		assertTrue("1.1", a1.isResolved());
		assertTrue("1.2", aFrag2.isResolved());
		assertFalse("1.3", aFrag1.isResolved());
		assertEquals("1.4", a1, aFrag2.getHost().getSupplier());
	}

	public void testReexportPackage() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "a");
		manifest.put(Constants.REQUIRE_BUNDLE, "C1; visibility:=reexport");
		BundleDescription a1_100 = state.getFactory().createBundleDescription(state, manifest, "a1_100", 0);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "b");
		manifest.put(Constants.IMPORT_PACKAGE, "a; bundle-symbolic-name=C1, c; d; bundle-symbolic-name=A1");
		BundleDescription b1_100 = state.getFactory().createBundleDescription(state, manifest, "b1_100", 1);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "C1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "c");
		manifest.put(Constants.REQUIRE_BUNDLE, "A1; visibility:=reexport, D1; visibility:=reexport");
		BundleDescription c1_100 = state.getFactory().createBundleDescription(state, manifest, "c1_100", 2);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "D1");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		manifest.put(Constants.EXPORT_PACKAGE, "d");
		manifest.put(Constants.REQUIRE_BUNDLE, "A1; visibility:=reexport");
		BundleDescription d1_100 = state.getFactory().createBundleDescription(state, manifest, "d1_100", 3);


		state.addBundle(a1_100);
		state.addBundle(b1_100);
		state.addBundle(c1_100);
		state.addBundle(d1_100);
		state.resolve();

		assertTrue("0.1", a1_100.isResolved());
		assertTrue("0.2", b1_100.isResolved());
		assertTrue("0.3", c1_100.isResolved());
		assertTrue("0.4", d1_100.isResolved());

		// this assumes getResolvedImports will return the imports in the same order they are specified in the Import-Package header
		ExportPackageDescription[] b1ResolvedImports = b1_100.getResolvedImports();
		assertEquals("1.1", 3, b1ResolvedImports.length);
		assertEquals("1.2", b1ResolvedImports[0].getExporter(), c1_100);
		assertEquals("1.3", b1ResolvedImports[1].getExporter(), a1_100);
		assertEquals("1.4", b1ResolvedImports[2].getExporter(), a1_100);
	}

	public void testPlatformProperties() throws BundleException {
		State state = buildEmptyState();
		int bundleID = 0;
		// test the selection algorithm of the resolver to pick the bundles which
		// resolve the largest set of bundles; with fragments using Import-Package
		Hashtable manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.osgi");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		BundleDescription systemBundle = state.getFactory().createBundleDescription(state, manifest, "org.eclipse.osgi", bundleID++);

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.IMPORT_PACKAGE, "pkg.b, pkg.system.b");
		manifest.put(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "J2SE-1.4");
		BundleDescription a = state.getFactory().createBundleDescription(state, manifest, "A", bundleID++);

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "pkg.b");
		manifest.put(Constants.IMPORT_PACKAGE, "pkg.system.b");
		manifest.put(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "J2SE-1.2");
		BundleDescription b = state.getFactory().createBundleDescription(state, manifest, "B", bundleID++);

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "system.b");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "pkg.system.b");
		manifest.put(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "J2SE-1.2");
		BundleDescription systemB = state.getFactory().createBundleDescription(state, manifest, "system.b", bundleID++);

		Dictionary[] props = new Dictionary[] {new Hashtable(), new Hashtable()};
		props[0].put("org.osgi.framework.system.packages", "pkg.system.a, pkg.system.c");
		props[0].put("org.osgi.framework.executionenvironment", "J2SE-1.2");
		props[1].put("org.osgi.framework.system.packages", "pkg.system.a, pkg.system.b, pkg.system.c");
		props[1].put("org.osgi.framework.executionenvironment", "J2SE-1.4");

		state.setPlatformProperties(props);
		state.addBundle(systemBundle);
		state.addBundle(a);
		state.addBundle(b);
		state.addBundle(systemB);
		state.resolve();

		assertTrue("1.0", systemBundle.isResolved());
		assertTrue("1.1", a.isResolved());
		assertTrue("1.2", b.isResolved());
		assertTrue("1.3", systemB.isResolved());

		assertTrue("2.0", a.getResolvedImports()[1].getExporter() == systemBundle);
		assertTrue("2.1", b.getResolvedImports()[0].getExporter() == systemB);

		// now test the uses clause for pkg.b such that bundle 'A' will be forced to used
		// pkg.system from bundle 'system.b'
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put(Constants.EXPORT_PACKAGE, "pkg.b; uses:=\"pkg.system.b\"");
		manifest.put(Constants.IMPORT_PACKAGE, "pkg.system.b");
		manifest.put(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "J2SE-1.2");
		BundleDescription b_updated = state.getFactory().createBundleDescription(state, manifest, "B", b.getBundleId());
		state.updateBundle(b_updated);
		state.resolve(new BundleDescription[] {b_updated});

		assertTrue("3.0", systemBundle.isResolved());
		assertTrue("3.1", a.isResolved());
		assertTrue("3.2", b_updated.isResolved());
		assertTrue("3.3", systemB.isResolved());

		assertTrue("2.0", a.getResolvedImports()[1].getExporter() == systemB);
		assertTrue("2.1", b_updated.getResolvedImports()[0].getExporter() == systemB);
	}


	public void testPlatformPropertiesBug188075() throws BundleException, IOException {
		State state = buildEmptyState();
		int bundleID = 0;

		Hashtable manifest = new Hashtable();

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "A");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put("Eclipse-PlatformFilter", "(!(test=value))");
		BundleDescription a = state.getFactory().createBundleDescription(state, manifest, "A", bundleID++);

		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "B");
		manifest.put(Constants.BUNDLE_VERSION, "1.0");
		manifest.put("Eclipse-PlatformFilter", "(test=value)");
		BundleDescription b = state.getFactory().createBundleDescription(state, manifest, "B", bundleID++);

		Dictionary props = new Hashtable();
		props.put("test", "value");

		state.setPlatformProperties(props);
		state.addBundle(a);
		state.addBundle(b);
		state.resolve();

		assertFalse("1.1", a.isResolved());
		assertTrue("1.2", b.isResolved());

		BundleContext context = OSGiTestsActivator.getContext();
		File stateCache = context.getDataFile("statecache");
		stateCache.mkdirs();
		StateObjectFactory.defaultFactory.writeState(state, stateCache);
		state = StateObjectFactory.defaultFactory.readState(stateCache);
		props = state.getPlatformProperties()[0];
		assertEquals("2.0", "value",  props.get("test"));
		BundleDescription aCache = state.getBundle("A", null);
		BundleDescription bCache = state.getBundle("B", null);
		assertFalse("2.1", aCache.isResolved());
		assertTrue("2.2", bCache.isResolved());
	}


	private ExportPackageDescription[] isConsistent(ExportPackageDescription[] pkgs1, ExportPackageDescription[] pkgs2) {
		for (int i = 0; i < pkgs1.length; i++)
			for (int j = 0; j < pkgs2.length; j++)
				if (pkgs1[i].getName().equals(pkgs2[j].getName()) && pkgs1[i] != pkgs2[j])
					return new ExportPackageDescription[] {pkgs1[i], pkgs2[j]}; 
		return null;
	}

	private boolean contains(Object[] array, Object element) {
		for (int i = 0; i < array.length; i++)
			if (array[i].equals(element))
				return true;
		return false;
	}

	public void testSelectionPolicy() throws BundleException {
		State state = buildEmptyState();
		Resolver resolver = state.getResolver();
		resolver.setSelectionPolicy(new Comparator() {
			public int compare(Object o1, Object o2) {
				if (!(o1 instanceof BaseDescription) || !(o2 instanceof BaseDescription))
					throw new IllegalArgumentException();
				Version v1 = null;
				Version v2 = null;
				v1 = ((BaseDescription) o1).getVersion();
				v2 = ((BaseDescription) o2).getVersion();
				// only take version in to account and use lower versions over higher ones
				return v1.compareTo(v2);
			}
		});
		Hashtable manifest = new Hashtable();
		long bundleID = 0;
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost100 = state.getFactory().createBundleDescription(state, manifest, "test.host100", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testHost101 = state.getFactory().createBundleDescription(state, manifest, "test.host101", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag100 = state.getFactory().createBundleDescription(state, manifest, "test.frag100", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testFrag101 = state.getFactory().createBundleDescription(state, manifest, "test.frag101", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.dependent; singleton:=true");
		manifest.put(Constants.REQUIRE_BUNDLE, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testDependent = state.getFactory().createBundleDescription(state, manifest, "test.frag101", bundleID++);

		state.addBundle(testHost100);
		state.addBundle(testFrag100);
		state.addBundle(testHost101);
		state.addBundle(testFrag101);
		state.addBundle(testDependent);
		state.resolve();
		assertTrue("1.0", testHost100.isResolved());
		assertFalse("1.1", testHost101.isResolved());
		assertTrue("1.2", testFrag100.isResolved());
		assertFalse("1.3", testFrag101.isResolved());
		assertTrue("1.4", testDependent.isResolved());
	}

	public void testBug187616() throws BundleException {
		State state = buildEmptyState();
		Hashtable manifest = new Hashtable();
		long bundleID = 0;
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testHost100 = state.getFactory().createBundleDescription(state, manifest, "test.host100", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.host; singleton:=true");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testHost101 = state.getFactory().createBundleDescription(state, manifest, "test.host101", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testFrag100 = state.getFactory().createBundleDescription(state, manifest, "test.frag100", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.frag; singleton:=true");
		manifest.put(Constants.FRAGMENT_HOST, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.1");
		BundleDescription testFrag101 = state.getFactory().createBundleDescription(state, manifest, "test.frag101", bundleID++);

		manifest = new Hashtable();
		manifest.put(Constants.BUNDLE_MANIFESTVERSION, "2");
		manifest.put(Constants.BUNDLE_SYMBOLICNAME, "test.dependent; singleton:=true");
		manifest.put(Constants.REQUIRE_BUNDLE, "test.host; bundle-version=\"[1.0.0,2.0.0)\"");
		manifest.put(Constants.BUNDLE_VERSION, "1.0.0");
		BundleDescription testDependent = state.getFactory().createBundleDescription(state, manifest, "test.frag101", bundleID++);

		state.addBundle(testHost100);
		state.addBundle(testFrag100);
		state.addBundle(testHost101);
		state.addBundle(testFrag101);
		state.addBundle(testDependent);
		StateDelta stateDelta = state.resolve();
		assertFalse("1.0", testHost100.isResolved());
		assertTrue("1.1", testHost101.isResolved());
		assertFalse("1.2", testFrag100.isResolved());
		assertTrue("1.3", testFrag101.isResolved());
		assertTrue("1.4", testDependent.isResolved());
		BundleDelta[] bDelta = stateDelta.getChanges(BundleDelta.ADDED | BundleDelta.RESOLVED, false);
		assertTrue("2.0", bDelta.length == 5);
	}
}
//testFragmentUpdateNoVersionChanged()
//testFragmentUpdateVersionChanged()
//testHostUpdateNoVersionChanged()
//testHostUpdateVersionChanged()

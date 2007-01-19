/*******************************************************************************
 * Copyright (c) 2004, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.module;

import java.util.*;
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.debug.FrameworkDebugOptions;
import org.eclipse.osgi.internal.resolver.BundleDescriptionImpl;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.*;

public class ResolverImpl implements org.eclipse.osgi.service.resolver.Resolver {
	// Debug fields
	private static final String RESOLVER = FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME + "/resolver"; //$NON-NLS-1$
	private static final String OPTION_DEBUG = RESOLVER + "/debug";//$NON-NLS-1$
	private static final String OPTION_WIRING = RESOLVER + "/wiring"; //$NON-NLS-1$
	private static final String OPTION_IMPORTS = RESOLVER + "/imports"; //$NON-NLS-1$
	private static final String OPTION_REQUIRES = RESOLVER + "/requires"; //$NON-NLS-1$
	private static final String OPTION_GENERICS = RESOLVER + "/generics"; //$NON-NLS-1$
	private static final String OPTION_GROUPING = RESOLVER + "/grouping"; //$NON-NLS-1$
	private static final String OPTION_CYCLES = RESOLVER + "/cycles"; //$NON-NLS-1$
	public static boolean DEBUG = false;
	public static boolean DEBUG_WIRING = false;
	public static boolean DEBUG_IMPORTS = false;
	public static boolean DEBUG_REQUIRES = false;
	public static boolean DEBUG_GENERICS = false;
	public static boolean DEBUG_GROUPING = false;
	public static boolean DEBUG_CYCLES = false;

	private static String[][] CURRENT_EES;

	// The State associated with this resolver
	private State state;
	// Used to check permissions for import/export, provide/require, host/fragment
	private PermissionChecker permissionChecker;
	// Set of bundles that are pending removal
	private MappedList removalPending = new MappedList();
	// Indicates whether this resolver has been initialized
	private boolean initialized = false;

	// Repository for exports
	private VersionHashMap resolverExports = null;
	// Repository for bundles
	private VersionHashMap resolverBundles = null;
	// Repository for generics
	private VersionHashMap resolverGenerics = null;
	// List of unresolved bundles
	private ArrayList unresolvedBundles = null;
	// Keys are BundleDescriptions, values are ResolverBundles
	private HashMap bundleMapping = null;
	private GroupingChecker groupingChecker;
	private Comparator selectionPolicy;
	private boolean developmentMode = false;

	public ResolverImpl(BundleContext context, boolean checkPermissions) {
		this.permissionChecker = new PermissionChecker(context, checkPermissions, this);
	}

	PermissionChecker getPermissionChecker() {
		return permissionChecker;
	}

	// Initializes the resolver
	private void initialize() {
		resolverExports = new VersionHashMap(this);
		resolverBundles = new VersionHashMap(this);
		resolverGenerics = new VersionHashMap(this);
		unresolvedBundles = new ArrayList();
		bundleMapping = new HashMap();
		BundleDescription[] bundles = state.getBundles();
		groupingChecker = new GroupingChecker();

		ArrayList fragmentBundles = new ArrayList();
		// Add each bundle to the resolver's internal state
		for (int i = 0; i < bundles.length; i++)
			initResolverBundle(bundles[i], fragmentBundles, false);
		// Add each removal pending bundle to the resolver's internal state
		Object[] removedBundles = removalPending.getAllValues();
		for (int i = 0; i < removedBundles.length; i++)
			initResolverBundle((BundleDescription) removedBundles[i], fragmentBundles, true);
		// Iterate over the resolved fragments and attach them to their hosts
		for (Iterator iter = fragmentBundles.iterator(); iter.hasNext();) {
			ResolverBundle fragment = (ResolverBundle) iter.next();
			BundleDescription[] hosts = ((HostSpecification) fragment.getHost().getVersionConstraint()).getHosts();
			for (int i = 0; i < hosts.length; i++) {
				ResolverBundle host = (ResolverBundle) bundleMapping.get(hosts[i]);
				if (host != null)
					// Do not add fragment exports here because they would have been added by the host above.
					host.attachFragment(fragment, false);
			}
		}
		rewireBundles(); // Reconstruct wirings
		ResolverBundle[] initBundles = (ResolverBundle[]) bundleMapping.values().toArray(new ResolverBundle[bundleMapping.size()]);
		for (int i = 0; i < initBundles.length; i++)
			// only initialize grouping constraint for resolved bundles; 
			// we add the constraints for unresolved bundles before we start a resolve opertation
			if (initBundles[i].isResolved())
				groupingChecker.addInitialGroupingConstraints(initBundles[i]);
		setDebugOptions();
		initialized = true;
	}

	private void initResolverBundle(BundleDescription bundleDesc, ArrayList fragmentBundles, boolean pending) {
		ResolverBundle bundle = new ResolverBundle(bundleDesc, this);
		bundleMapping.put(bundleDesc, bundle);
		if (!pending || bundleDesc.isResolved()) {
			resolverExports.put(bundle.getExportPackages());
			resolverBundles.put(bundle.getName(), bundle);
			resolverGenerics.put(bundle.getGenericCapabilities());
		}
		if (bundleDesc.isResolved()) {
			bundle.setState(ResolverBundle.RESOLVED);
			if (bundleDesc.getHost() != null)
				fragmentBundles.add(bundle);
		} else {
			if (!pending)
				unresolvedBundles.add(bundle);
		}
	}

	// Re-wire previously resolved bundles
	private void rewireBundles() {
		ArrayList visited = new ArrayList(bundleMapping.size());
		for (Iterator iter = bundleMapping.values().iterator(); iter.hasNext();) {
			ResolverBundle rb = (ResolverBundle) iter.next();
			if (!rb.getBundle().isResolved() || rb.isFragment())
				continue;
			rewireBundle(rb, visited);
		}
	}

	private void rewireBundle(ResolverBundle rb, ArrayList visited) {
		if (visited.contains(rb))
			return;
		visited.add(rb);
		// Wire requires to bundles
		BundleConstraint[] requires = rb.getRequires();
		for (int i = 0; i < requires.length; i++) {
			rewireRequire(requires[i], visited);
		}
		// Wire imports to exports
		ResolverImport[] imports = rb.getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			rewireImport(imports[i], visited);
		}
		// Wire generics
		GenericConstraint[] genericRequires = rb.getGenericRequires();
		for (int i = 0; i < genericRequires.length; i++)
			rewireGeneric(genericRequires[i], visited);
	}

	private void rewireGeneric(GenericConstraint constraint, ArrayList visited) {
		if (constraint.getMatchingCapabilities() != null)
			return;
		GenericDescription[] suppliers = ((GenericSpecification) constraint.getVersionConstraint()).getSuppliers();
		if (suppliers == null)
			return;
		Object[] matches = resolverGenerics.get(constraint.getName());
		for (int i = 0; i < matches.length; i++) {
			GenericCapability match = (GenericCapability) matches[i];
			for (int j = 0; j < suppliers.length; j++)
				if (match.getBaseDescription() == suppliers[j])
					constraint.setMatchingCapability(match);
		}
		GenericCapability[] matchingCapabilities = constraint.getMatchingCapabilities();
		if (matchingCapabilities != null)
			for (int i = 0; i < matchingCapabilities.length; i++)
				rewireBundle(matchingCapabilities[i].getResolverBundle(), visited);
	}

	private void rewireRequire(BundleConstraint req, ArrayList visited) {
		if (req.getMatchingBundle() != null)
			return;
		ResolverBundle matchingBundle = (ResolverBundle) bundleMapping.get(req.getVersionConstraint().getSupplier());
		req.setMatchingBundle(matchingBundle);
		if (matchingBundle == null && !req.isOptional()) {
			System.err.println("Could not find matching bundle for " + req.getVersionConstraint()); //$NON-NLS-1$
			// TODO log error!!
		}
		if (matchingBundle != null) {
			rewireBundle(matchingBundle, visited);
		}
	}

	private void rewireImport(ResolverImport imp, ArrayList visited) {
		if (imp.isDynamic() || imp.getMatchingExport() != null)
			return;
		// Re-wire 'imp'
		ResolverExport matchingExport = null;
		ExportPackageDescription importSupplier = (ExportPackageDescription) imp.getVersionConstraint().getSupplier();
		ResolverBundle exporter = importSupplier == null ? null : (ResolverBundle) bundleMapping.get(importSupplier.getExporter());
		Object[] matches = resolverExports.get(imp.getName());
		for (int j = 0; j < matches.length; j++) {
			ResolverExport export = (ResolverExport) matches[j];
			if (export.getExporter() == exporter && imp.isSatisfiedBy(export)) {
				matchingExport = export;
				break;
			}
		}
		imp.setMatchingExport(matchingExport);
		// Check if we wired to a reprovided package (in which case the ResolverExport doesn't exist)
		if (matchingExport == null && exporter != null) {
			ResolverExport reprovidedExport = new ResolverExport(exporter, importSupplier);
			if (exporter.getExport(imp.getName()) == null) {
				exporter.addExport(reprovidedExport);
				resolverExports.put(reprovidedExport.getName(), reprovidedExport);
			}
			imp.setMatchingExport(reprovidedExport);
		}
		// If we still have a null wire and it's not optional, then we have an error
		if (imp.getMatchingExport() == null && !imp.isOptional()) {
			System.err.println("Could not find matching export for " + imp.getVersionConstraint()); //$NON-NLS-1$
			// TODO log error!!
		}
		if (imp.getMatchingExport() != null) {
			rewireBundle(imp.getMatchingExport().getExporter(), visited);
		}
	}

	// Checks a bundle to make sure it is valid.  If this method returns false for
	// a given bundle, then that bundle will not even be considered for resolution
	private boolean isResolvable(BundleDescription bundle, Dictionary[] platformProperties, ArrayList rejectedSingletons) {
		// check if this is a rejected singleton
		if (rejectedSingletons.contains(bundle))
			return false;
		// Check for singletons
		if (bundle.isSingleton()) {
			Object[] sameName = resolverBundles.get(bundle.getName());
			if (sameName.length > 1) // Need to check if one is already resolved
				for (int i = 0; i < sameName.length; i++) {
					if (sameName[i] == bundle || !((ResolverBundle) sameName[i]).getBundle().isSingleton())
						continue; // Ignore the bundle we are resolving and non-singletons
					if (((ResolverBundle) sameName[i]).getBundle().isResolved()) {
						rejectedSingletons.add(bundle);
						return false; // Must fail since there is already a resolved bundle
					}
				}
		}
		// check the required execution environment
		String[] ees = bundle.getExecutionEnvironments();
		boolean matchedEE = ees.length == 0;
		if (!matchedEE)
			for (int i = 0; i < ees.length && !matchedEE; i++)
				for (int j = 0; j < CURRENT_EES.length && !matchedEE; j++)
					for (int k = 0; k < CURRENT_EES[j].length && !matchedEE; k++)
						if (CURRENT_EES[j][k].equals(ees[i])) {
							((BundleDescriptionImpl) bundle).setEquinoxEE(j);
							matchedEE = true;
						}
		if (!matchedEE) {
			StringBuffer bundleEE = new StringBuffer(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT.length() + 20);
			bundleEE.append(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT).append(": "); //$NON-NLS-1$
			for (int i = 0; i < ees.length; i++) {
				if (i > 0)
					bundleEE.append(","); //$NON-NLS-1$
				bundleEE.append(ees[i]);
			}
			state.addResolverError(bundle, ResolverError.MISSING_EXECUTION_ENVIRONMENT, bundleEE.toString(), null);
			return false;
		}

		// check the platform filter
		String platformFilter = bundle.getPlatformFilter();
		if (platformFilter == null)
			return true;
		if (platformProperties == null)
			return false;
		try {
			Filter filter = FrameworkUtil.createFilter(platformFilter);
			for (int i = 0; i < platformProperties.length; i++)
				if (filter.match(platformProperties[i]))
					return true;
		} catch (InvalidSyntaxException e) {
			// return false below
		}
		state.addResolverError(bundle, ResolverError.PLATFORM_FILTER, platformFilter, null);
		return false;
	}

	// Attach fragment to its host
	private void attachFragment(ResolverBundle bundle, ArrayList rejectedSingletons) {
		if (!bundle.isFragment() || !bundle.isResolvable() || rejectedSingletons.contains(bundle.getBundle()))
			return;
		// no need to select singletons now; it will be done when we select the rest of the singleton bundles (bug 152042)
		// find all available hosts to attach to.
		boolean foundMatch = false;
		BundleConstraint hostConstraint = bundle.getHost();
		Object[] hosts = resolverBundles.get(hostConstraint.getVersionConstraint().getName());
		for (int i = 0; i < hosts.length; i++)
			if (((ResolverBundle) hosts[i]).isResolvable() && hostConstraint.isSatisfiedBy((ResolverBundle) hosts[i])) {
				foundMatch = true;
				resolverExports.put(((ResolverBundle) hosts[i]).attachFragment(bundle, true));
			}
		if (!foundMatch)
			state.addResolverError(bundle.getBundle(), ResolverError.MISSING_FRAGMENT_HOST, bundle.getHost().getVersionConstraint().toString(), bundle.getHost().getVersionConstraint());
	}

	public synchronized void resolve(BundleDescription[] reRefresh, Dictionary[] platformProperties) {
		if (DEBUG)
			ResolverImpl.log("*** BEGIN RESOLUTION ***"); //$NON-NLS-1$
		if (state == null)
			throw new IllegalStateException("RESOLVER_NO_STATE"); //$NON-NLS-1$

		if (!initialized)
			initialize();
		developmentMode = platformProperties.length == 0 ? false : org.eclipse.osgi.framework.internal.core.Constants.DEVELOPMENT_MODE.equals(platformProperties[0].get(org.eclipse.osgi.framework.internal.core.Constants.OSGI_RESOLVER_MODE));
		reRefresh = addHostsFromFragmentConstraints(reRefresh);
		// Unresolve all the supplied bundles and their dependents
		if (reRefresh != null)
			for (int i = 0; i < reRefresh.length; i++) {
				ResolverBundle rb = (ResolverBundle) bundleMapping.get(reRefresh[i]);
				if (rb != null)
					unresolveBundle(rb, false);
			}
		// reorder exports and bundles after unresolving the bundles
		resolverExports.reorder();
		resolverBundles.reorder();
		resolverGenerics.reorder();
		// always get the latest EEs
		getCurrentEEs(platformProperties);
		// keep a list of rejected singltons
		ArrayList rejectedSingletons = new ArrayList();
		boolean resolveOptional = platformProperties.length == 0 ? false : "true".equals(platformProperties[0].get("osgi.resolveOptional")); //$NON-NLS-1$//$NON-NLS-2$
		ResolverBundle[] currentlyResolved = null;
		if (resolveOptional) {
			BundleDescription[] resolvedBundles = state.getResolvedBundles();
			currentlyResolved = new ResolverBundle[resolvedBundles.length];
			for (int i = 0; i < resolvedBundles.length; i++)
				currentlyResolved[i] = (ResolverBundle) bundleMapping.get(resolvedBundles[i]);
		}
		// attempt to resolve all unresolved bundles
		ResolverBundle[] bundles = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
		resolveBundles(bundles, platformProperties, rejectedSingletons);
		if (selectSingletons(bundles, rejectedSingletons)) {
			// a singleton was unresolved as a result of selecting a different version
			// try to resolve unresolved bundles again; this will attempt to use the selected singleton
			bundles = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
			resolveBundles(bundles, platformProperties, rejectedSingletons);
		}
		for (Iterator rejected = rejectedSingletons.iterator(); rejected.hasNext();) {
			BundleDescription reject = (BundleDescription) rejected.next();
			BundleDescription sameName = state.getBundle(reject.getSymbolicName(), null);
			state.addResolverError(reject, ResolverError.SINGLETON_SELECTION, sameName.toString(), null);
		}
		if (resolveOptional)
			resolveOptionalConstraints(currentlyResolved);
		if (DEBUG)
			ResolverImpl.log("*** END RESOLUTION ***"); //$NON-NLS-1$
	}

	private BundleDescription[] addHostsFromFragmentConstraints(BundleDescription[] reRefresh) {
		if (!developmentMode)
			return reRefresh; // we don't care about this unless we are in development mode
		// when in develoment mode we need to reRefresh hosts 
		// of unresolved fragments that add new constraints 
		HashSet additionalRefresh = new HashSet();
		ResolverBundle[] bundles = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
		for (int i = 0; i < bundles.length; i++) {
			if (!bundles[i].isFragment())
				continue;
			ImportPackageSpecification[] newImports = bundles[i].getBundle().getImportPackages();
			BundleSpecification[] newRequires = bundles[i].getBundle().getRequiredBundles();
			if (newImports.length == 0 && newRequires.length == 0)
				continue; // the fragment does not have its own constraints
			BundleConstraint hostConstraint = bundles[i].getHost();
			Object[] hosts = resolverBundles.get(hostConstraint.getVersionConstraint().getName());
			for (int j = 0; j < hosts.length; j++)
				if (hostConstraint.isSatisfiedBy((ResolverBundle) hosts[j]) && ((ResolverBundle) hosts[j]).isResolved())
					// we found a host that is resolved;
					// add it to the set of bundle to refresh so we can ensure this fragment is allowed to resolve
					additionalRefresh.add(((ResolverBundle) hosts[j]).getBundle());
		}
		if (additionalRefresh.size() == 0)
			return reRefresh; // no new bundles found to refresh
		// add the original reRefresh bundles to the set
		if (reRefresh != null)
			for (int i = 0; i < reRefresh.length; i++)
				additionalRefresh.add(reRefresh[i]);
		return (BundleDescription[]) additionalRefresh.toArray(new BundleDescription[additionalRefresh.size()]);
	}

	private void resolveOptionalConstraints(ResolverBundle[] bundles) {
		for (int i = 0; i < bundles.length; i++) {
			if (bundles[i] != null)
				resolveOptionalConstraints(bundles[i]);
		}
	}

	private void resolveOptionalConstraints(ResolverBundle bundle) {
		BundleConstraint[] requires = bundle.getRequires();
		ArrayList cycle = new ArrayList();
		boolean resolvedOptional = false;
		for (int i = 0; i < requires.length; i++)
			if (requires[i].isOptional() && requires[i].getMatchingBundle() == null) {
				cycle.clear();
				resolveRequire(requires[i], cycle);
				if (requires[i].getMatchingBundle() != null)
					resolvedOptional = true;
			}
		ResolverImport[] imports = bundle.getImportPackages();
		for (int i = 0; i < imports.length; i++)
			if (imports[i].isOptional() && imports[i].getMatchingExport() == null) {
				cycle.clear();
				resolveImport(imports[i], true, cycle);
				if (imports[i].getMatchingExport() != null)
					resolvedOptional = true;
			}
		if (resolvedOptional) {
			state.resolveBundle(bundle.getBundle(), false, null, null, null, null);
			stateResolveConstraints(bundle);
			stateResolveBundle(bundle);
		}
	}

	private void getCurrentEEs(Dictionary[] platformProperties) {
		CURRENT_EES = new String[platformProperties.length][];
		for (int i = 0; i < platformProperties.length; i++) {
			String eeSpecs = (String) platformProperties[i].get(Constants.FRAMEWORK_EXECUTIONENVIRONMENT);
			CURRENT_EES[i] = ManifestElement.getArrayFromList(eeSpecs, ","); //$NON-NLS-1$
		}
	}

	private void resolveBundles(ResolverBundle[] bundles, Dictionary[] platformProperties, ArrayList rejectedSingletons) {
		// First check that all the meta-data is valid for each unresolved bundle
		// This will reset the resolvable flag for each bundle
		for (int i = 0; i < bundles.length; i++) {
			state.removeResolverErrors(bundles[i].getBundle());
			// if in development mode then make all bundles resolvable
			// we still want to call isResolvable here to populate any possible ResolverErrors for the bundle
			bundles[i].setResolvable(isResolvable(bundles[i].getBundle(), platformProperties, rejectedSingletons) || developmentMode);
			bundles[i].clearRefs();
			groupingChecker.removeAllExportConstraints(bundles[i]);
		}

		// First attach all fragments to the matching hosts
		for (int i = 0; i < bundles.length; i++)
			attachFragment(bundles[i], rejectedSingletons);

		// add initial grouping constraints after fragments have been attached
		for (int i = 0; i < bundles.length; i++)
			groupingChecker.addInitialGroupingConstraints(bundles[i]);
		// Lists of cyclic dependencies recording during resolving
		ArrayList cycle = new ArrayList(1); // start small
		ArrayList resolvedBundles = new ArrayList(bundles.length);
		// Attempt to resolve all unresolved bundles
		for (int i = 0; i < bundles.length; i++) {
			if (DEBUG)
				ResolverImpl.log("** RESOLVING " + bundles[i] + " **"); //$NON-NLS-1$ //$NON-NLS-2$
			cycle.clear();
			resolveBundle(bundles[i], cycle);
			// Check for any bundles involved in a cycle.
			// if any bundles in the cycle are not resolved then we need to resolve the resolvable ones
			checkCycle(cycle);
			if (bundles[i].isResolvable())
				resolvedBundles.add(bundles[i]);
		}

		// Resolve all fragments that are still attached to at least one host.
		if (unresolvedBundles.size() > 0) {
			ResolverBundle[] unresolved = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
			for (int i = 0; i < unresolved.length; i++)
				resolveFragment(unresolved[i]);
		}

		if (DEBUG_WIRING)
			printWirings();
		// set the resolved status of the bundles in the State
		stateResolveBundles(bundles);
	}

	private void checkCycle(ArrayList cycle) {
		int cycleSize = cycle.size();
		if (cycleSize == 0)
			return;
		for (int i = cycleSize - 1; i >= 0; i--) {
			ResolverBundle cycleBundle = (ResolverBundle) cycle.get(i);
			// clear grouping (uses) constraints so we can do proper constaint checking now that the cycle is resolved
			groupingChecker.removeAllExportConstraints(cycleBundle);
			groupingChecker.addInitialGroupingConstraints(cycleBundle);
			if (!cycleBundle.isResolvable())
				cycle.remove(i); // remove this from the list of bundles that need reresolved
		}
		boolean reresolveCycle = cycle.size() != cycleSize || !isCycleConsistent(cycle); //we removed an unresolvable bundle; must reresolve remaining cycle
		for (int i = 0; i < cycle.size(); i++) {
			ResolverBundle rb = (ResolverBundle) cycle.get(0);
			// Check that we haven't wired to any dropped exports
			ResolverImport[] imports = rb.getImportPackages();
			for (int j = 0; j < imports.length; j++)
				// check for dropped exports
				if (imports[j].getMatchingExport() != null && imports[j].getMatchingExport().isDropped()) {
					imports[j].addUnresolvableWiring(imports[j].getMatchingExport().getExporter());
					reresolveCycle = true;
				}
		}
		if (reresolveCycle) {
			for (int i = 0; i < cycle.size(); i++) {
				ResolverBundle cycleBundle = (ResolverBundle) cycle.get(i);
				groupingChecker.removeAllExportConstraints(cycleBundle);
				groupingChecker.addInitialGroupingConstraints(cycleBundle);
				cycleBundle.clearWires(false);
				cycleBundle.clearRefs();
			}
			groupingChecker.setCheckCycles(true); // need to do the expensive cycle checks now
			ArrayList innerCycle = new ArrayList(cycle.size());
			for (int i = 0; i < cycle.size(); i++)
				resolveBundle((ResolverBundle) cycle.get(i), innerCycle);
			groupingChecker.setCheckCycles(false); // disable the expensive cycle checks
			checkCycle(innerCycle);
		} else {
			for (int i = 0; i < cycle.size(); i++) {
				if (DEBUG || DEBUG_CYCLES)
					ResolverImpl.log("Pushing " + cycle.get(i) + " to RESOLVED"); //$NON-NLS-1$ //$NON-NLS-2$
				setBundleResolved((ResolverBundle) cycle.get(i));
			}
		}
	}

	// checks all the uses constraints of a cycle to make sure they 
	// are consistent now that the cycle has been resolved.
	private boolean isCycleConsistent(ArrayList cycle) {
		for (Iterator iter = cycle.iterator(); iter.hasNext();) {
			ResolverBundle bundle = (ResolverBundle) iter.next();
			BundleConstraint[] requires = bundle.getRequires();
			for (int i = 0; i < requires.length; i++)
				if (requires[i].getMatchingBundle() != null && groupingChecker.isConsistent(requires[i], requires[i].getMatchingBundle()) != null)
					return false;
			ResolverImport[] imports = bundle.getImportPackages();
			for (int i = 0; i < imports.length; i++)
				if (imports[i].getMatchingExport() != null && groupingChecker.isConsistent(imports[i], imports[i].getMatchingExport()) != null)
					return false;
		}
		return true;
	}

	private boolean selectSingletons(ResolverBundle[] bundles, ArrayList rejectedSingletons) {
		if (developmentMode)
			return false; // do no want to unresolve singletons in development mode
		boolean result = false;
		for (int i = 0; i < bundles.length; i++) {
			BundleDescription bundleDesc = bundles[i].getBundle();
			if (!bundleDesc.isSingleton() || !bundleDesc.isResolved() || rejectedSingletons.contains(bundleDesc))
				continue;
			Object[] sameName = resolverBundles.get(bundleDesc.getName());
			if (sameName.length > 1) { // Need to make a selection based off of num dependents
				for (int j = 0; j < sameName.length; j++) {
					BundleDescription sameNameDesc = ((VersionSupplier) sameName[j]).getBundle();
					ResolverBundle sameNameBundle = (ResolverBundle) sameName[j];
					if (sameName[j] == bundles[i] || !sameNameDesc.isSingleton() || !sameNameDesc.isResolved() || rejectedSingletons.contains(sameNameDesc))
						continue; // Ignore the bundle we are selecting, non-singletons, and non-resolved
					result = true;
					boolean rejectedPolicy = selectionPolicy != null ? selectionPolicy.compare(sameNameDesc, bundleDesc) < 0 : sameNameDesc.getVersion().compareTo(bundleDesc.getVersion()) > 0;
					if (rejectedPolicy && sameNameBundle.getRefs() >= bundles[i].getRefs()) {
						// this bundle is not selected; add it to the rejected list
						if (!rejectedSingletons.contains(bundles[i].getBundle()))
							rejectedSingletons.add(bundles[i].getBundle());
						break;
					}
					// we did not select the sameNameDesc; add the bundle to the rejected list
					if (!rejectedSingletons.contains(sameNameDesc))
						rejectedSingletons.add(sameNameDesc);
				}
			}
		}
		// unresolve the rejected singletons
		for (Iterator rejects = rejectedSingletons.iterator(); rejects.hasNext();)
			unresolveBundle((ResolverBundle) bundleMapping.get(rejects.next()), false);
		return result;
	}

	private void resolveFragment(ResolverBundle fragment) {
		if (!fragment.isFragment())
			return;
		if (fragment.getHost().foundMatchingBundles()) {
			stateResolveFragConstraints(fragment);
			setBundleResolved(fragment);
		}
	}

	// This method will attempt to resolve the supplied bundle and any bundles that it is dependent on
	private boolean resolveBundle(ResolverBundle bundle, ArrayList cycle) {
		if (bundle.isFragment())
			return false;
		if (!bundle.isResolvable()) {
			if (DEBUG)
				ResolverImpl.log("  - " + bundle + " is unresolvable"); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
		if (bundle.getState() == ResolverBundle.RESOLVED) {
			// 'bundle' is already resolved so just return
			if (DEBUG)
				ResolverImpl.log("  - " + bundle + " already resolved"); //$NON-NLS-1$ //$NON-NLS-2$
			return true;
		} else if (bundle.getState() == ResolverBundle.UNRESOLVED) {
			// 'bundle' is UNRESOLVED so move to RESOLVING
			bundle.clearWires(true);
			setBundleResolving(bundle);
		}

		boolean failed = false;

		if (!failed) {
			GenericConstraint[] genericRequires = bundle.getGenericRequires();
			for (int i = 0; i < genericRequires.length; i++) {
				if (!resolveGenericReq(genericRequires[i], cycle)) {
					if (DEBUG || DEBUG_GENERICS)
						ResolverImpl.log("** GENERICS " + genericRequires[i].getVersionConstraint().getName() + "[" + genericRequires[i].getBundleDescription() + "] failed to resolve"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					state.addResolverError(genericRequires[i].getVersionConstraint().getBundle(), ResolverError.MISSING_GENERIC_CAPABILITY, genericRequires[i].getVersionConstraint().toString(), genericRequires[i].getVersionConstraint());
					if (genericRequires[i].isFromFragment()) {
						if (!developmentMode) // only detach fragments when not in devmode
							resolverExports.remove(bundle.detachFragment((ResolverBundle) bundleMapping.get(genericRequires[i].getVersionConstraint().getBundle()), null));
						continue;
					}
					failed = true;
					if (!developmentMode)
						break;
				}
			}
		}

		if (!failed) {
			// Iterate thru required bundles of 'bundle' trying to find matching bundles.
			BundleConstraint[] requires = bundle.getRequires();
			for (int i = 0; i < requires.length; i++) {
				if (!resolveRequire(requires[i], cycle)) {
					if (DEBUG || DEBUG_REQUIRES)
						ResolverImpl.log("** REQUIRE " + requires[i].getVersionConstraint().getName() + "[" + requires[i].getBundleDescription() + "] failed to resolve"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					state.addResolverError(requires[i].getVersionConstraint().getBundle(), ResolverError.MISSING_REQUIRE_BUNDLE, requires[i].getVersionConstraint().toString(), requires[i].getVersionConstraint());
					// If the require has failed to resolve and it is from a fragment, then remove the fragment from the host
					if (requires[i].isFromFragment()) {
						if (!developmentMode) // only detach fragments when not in devmode
							resolverExports.remove(bundle.detachFragment((ResolverBundle) bundleMapping.get(requires[i].getVersionConstraint().getBundle()), requires[i]));
						continue;
					}
					failed = true;
					if (!developmentMode) // in dev mode continue to next constraint
						break;
				}
			}
		}

		if (!failed) {
			// Iterate thru imports of 'bundle' trying to find matching exports.
			ResolverImport[] imports = bundle.getImportPackages();
			for (int i = 0; i < imports.length; i++) {
				// Only resolve non-dynamic imports here
				if (!imports[i].isDynamic() && !resolveImport(imports[i], true, cycle)) {
					if (DEBUG || DEBUG_IMPORTS)
						ResolverImpl.log("** IMPORT " + imports[i].getName() + "[" + imports[i].getBundleDescription() + "] failed to resolve"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					// If the import has failed to resolve and it is from a fragment, then remove the fragment from the host
					state.addResolverError(imports[i].getVersionConstraint().getBundle(), ResolverError.MISSING_IMPORT_PACKAGE, imports[i].getVersionConstraint().toString(), imports[i].getVersionConstraint());
					if (imports[i].isFromFragment()) {
						if (!developmentMode) // only detach fragments when not in devmode
							resolverExports.remove(bundle.detachFragment((ResolverBundle) bundleMapping.get(imports[i].getBundleDescription()), imports[i]));
						continue;
					}
					failed = true;
					if (!developmentMode) // in dev mode continue to next constraint
						break;
				}
			}
		}

		// check that fragment constraints are met by the constraints that got resolved to the host
		checkFragmentConstraints(bundle);

		// do some extra checking when in development mode to see if other resolver error occurred
		if (developmentMode && !failed && state.getResolverErrors(bundle.getBundle()).length > 0)
			failed = true;

		// Need to check that all mandatory imports are wired. If they are then
		// set the bundle RESOLVED, otherwise set it back to UNRESOLVED
		if (failed) {
			setBundleUnresolved(bundle, false, developmentMode);
			if (DEBUG)
				ResolverImpl.log(bundle + " NOT RESOLVED"); //$NON-NLS-1$
		} else if (!cycle.contains(bundle)) {
			setBundleResolved(bundle);
			if (DEBUG)
				ResolverImpl.log(bundle + " RESOLVED"); //$NON-NLS-1$
		}

		if (bundle.getState() == ResolverBundle.UNRESOLVED)
			bundle.setResolvable(false); // Set it to unresolvable so we don't attempt to resolve it again in this round

		// tell the state what we resolved the constraints to
		stateResolveConstraints(bundle);
		return bundle.getState() != ResolverBundle.UNRESOLVED;
	}

	private void checkFragmentConstraints(ResolverBundle bundle) {
		// get all currently attached fragments and ensure that any constraints
		// they have do not conflict with the constraints resolved to by the host
		ResolverBundle[] fragments = bundle.getFragments();
		for (int i = 0; i < fragments.length; i++) {
			BundleDescription fragment = fragments[i].getBundle();
			if (bundle.constraintsConflict(fragment, fragment.getImportPackages(), fragment.getRequiredBundles(), fragment.getGenericRequires()) && !developmentMode)
				// found some conflicts; detach the fragment
				resolverExports.remove(bundle.detachFragment(fragments[i], null));
		}
	}

	private boolean resolveGenericReq(GenericConstraint constraint, ArrayList cycle) {
		if (DEBUG_REQUIRES)
			ResolverImpl.log("Trying to resolve: " + constraint.getBundle() + ", " + constraint.getVersionConstraint()); //$NON-NLS-1$ //$NON-NLS-2$
		GenericCapability[] matchingCapabilities = constraint.getMatchingCapabilities();
		if (matchingCapabilities != null) {
			// Check for unrecorded cyclic dependency
			for (int i = 0; i < matchingCapabilities.length; i++)
				if (matchingCapabilities[i].getResolverBundle().getState() == ResolverBundle.RESOLVING)
					if (!cycle.contains(constraint.getBundle()))
						cycle.add(constraint.getBundle());
			if (DEBUG_REQUIRES)
				ResolverImpl.log("  - already wired"); //$NON-NLS-1$
			return true; // Already wired (due to grouping dependencies) so just return
		}
		Object[] capabilities = resolverGenerics.get(constraint.getVersionConstraint().getName());
		boolean result = false;
		for (int i = 0; i < capabilities.length; i++) {
			GenericCapability capability = (GenericCapability) capabilities[i];
			if (DEBUG_GENERICS)
				ResolverImpl.log("CHECKING GENERICS: " + capability.getBaseDescription()); //$NON-NLS-1$
			// Check if capability matches
			if (constraint.isSatisfiedBy(capability)) {
				capability.getResolverBundle().addRef(constraint.getBundle());
				if (result && (((GenericSpecification) constraint.getVersionConstraint()).getResolution() & GenericSpecification.RESOLUTION_MULTIPLE) == 0)
					continue; // found a match already and this is not a multiple constraint
				constraint.setMatchingCapability(capability); // Wire to the capability
				if (constraint.getBundle() == capability.getResolverBundle()) {
					result = true; // Wired to ourselves
					continue;
				}
				ResolverBundle[] capabilityHosts = capability.isFromFragment() ? capability.getResolverBundle().getHost().getMatchingBundles() : new ResolverBundle[] {capability.getResolverBundle()};
				boolean foundResolvedMatch = false;
				for (int j = 0; capabilityHosts != null && j < capabilityHosts.length; j++)
					// if in dev mode then allow a constraint to resolve to an unresolved bundle
					if (capabilityHosts[j].getState() == ResolverBundle.RESOLVED || (resolveBundle(capabilityHosts[j], cycle) || developmentMode)) {
						foundResolvedMatch |= !capability.isFromFragment() ? true : capability.getResolverBundle().getHost().getMatchingBundles() != null;
						// Check cyclic dependencies
						if (capabilityHosts[j].getState() == ResolverBundle.RESOLVING)
							if (!cycle.contains(capabilityHosts[j]))
								cycle.add(capabilityHosts[j]);
					}
				if (!foundResolvedMatch) {
					constraint.removeMatchingCapability(capability);
					continue; // constraint hasn't resolved
				}
				if (DEBUG_GENERICS)
					ResolverImpl.log("Found match: " + capability.getBaseDescription() + ". Wiring"); //$NON-NLS-1$ //$NON-NLS-2$
				result = true;
			}
		}
		return result ? true : (((GenericSpecification) constraint.getVersionConstraint()).getResolution() & GenericSpecification.RESOLUTION_OPTIONAL) != 0;
	}

	// Resolve the supplied import. Returns true if the import can be resolved, false otherwise
	private boolean resolveRequire(BundleConstraint req, ArrayList cycle) {
		if (DEBUG_REQUIRES)
			ResolverImpl.log("Trying to resolve: " + req.getBundle() + ", " + req.getVersionConstraint()); //$NON-NLS-1$ //$NON-NLS-2$
		if (req.getMatchingBundle() != null) {
			// Check for unrecorded cyclic dependency
			if (req.getMatchingBundle().getState() == ResolverBundle.RESOLVING)
				if (!cycle.contains(req.getBundle())) {
					cycle.add(req.getBundle());
					if (DEBUG_CYCLES)
						ResolverImpl.log("require-bundle cycle: " + req.getBundle() + " -> " + req.getMatchingBundle()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			if (DEBUG_REQUIRES)
				ResolverImpl.log("  - already wired"); //$NON-NLS-1$
			return true; // Already wired (due to grouping dependencies) so just return
		}
		Object[] bundles = resolverBundles.get(req.getVersionConstraint().getName());
		boolean result = false;
		for (int i = 0; i < bundles.length; i++) {
			ResolverBundle bundle = (ResolverBundle) bundles[i];
			if (DEBUG_REQUIRES)
				ResolverImpl.log("CHECKING: " + bundle.getBundle()); //$NON-NLS-1$
			// Check if export matches
			if (req.isSatisfiedBy(bundle)) {
				bundle.addRef(req.getBundle());
				if (result)
					continue;
				req.setMatchingBundle(bundle); // Wire to the bundle
				if (req.getBundle() == bundle) {
					result = true; // Wired to ourselves
					continue;
				}
				// if in dev mode then allow a constraint to resolve to an unresolved bundle
				if (bundle.getState() != ResolverBundle.RESOLVED && !resolveBundle(bundle, cycle) && !developmentMode) {
					req.setMatchingBundle(null);
					continue; // Bundle hasn't resolved
				}
				// Check cyclic dependencies
				if (bundle.getState() == ResolverBundle.RESOLVING)
					// If the bundle is RESOLVING, we have a cyclic dependency
					if (!cycle.contains(req.getBundle())) {
						cycle.add(req.getBundle());
						if (DEBUG_CYCLES)
							ResolverImpl.log("require-bundle cycle: " + req.getBundle() + " -> " + req.getMatchingBundle()); //$NON-NLS-1$ //$NON-NLS-2$
					}
				if (DEBUG_REQUIRES)
					ResolverImpl.log("Found match: " + bundle.getBundle() + ". Wiring"); //$NON-NLS-1$ //$NON-NLS-2$
				result = checkRequiresConstraints(req, req.getMatchingBundle());
			}
		}
		if (result || req.isOptional())
			return true; // If the req is optional then just return true

		return false;
	}

	private boolean checkRequiresConstraints(BundleConstraint req, ResolverBundle bundle) {
		if (groupingChecker.isConsistent(req, bundle) != null) {
			req.setMatchingBundle(null);
			state.addResolverError(req.getBundleDescription(), ResolverError.REQUIRE_BUNDLE_USES_CONFLICT, bundle.getBundle().toString(), req.getVersionConstraint());
			return req.isOptional();
		}
		return true;
	}

	// Resolve the supplied import. Returns true if the import can be resolved, false otherwise
	private boolean resolveImport(ResolverImport imp, boolean checkReexportsFromRequires, ArrayList cycle) {
		if (DEBUG_IMPORTS)
			ResolverImpl.log("Trying to resolve: " + imp.getBundle() + ", " + imp.getName()); //$NON-NLS-1$ //$NON-NLS-2$
		if (imp.getMatchingExport() != null) {
			// Check for unrecorded cyclic dependency
			if (imp.getMatchingExport().getExporter().getState() == ResolverBundle.RESOLVING)
				if (!cycle.contains(imp.getBundle())) {
					cycle.add(imp.getBundle());
					if (DEBUG_CYCLES)
						ResolverImpl.log("import-package cycle: " + imp.getBundle() + " -> " + imp.getMatchingExport() + " from " + imp.getMatchingExport().getBundle()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			if (DEBUG_IMPORTS)
				ResolverImpl.log("  - already wired"); //$NON-NLS-1$
			return true; // Already wired (due to grouping dependencies) so just return
		}
		boolean result = false;
		Object[] exports = resolverExports.get(imp.getName());
		exportsloop: for (int i = 0; i < exports.length; i++) {
			ResolverExport export = (ResolverExport) exports[i];
			if (DEBUG_IMPORTS)
				ResolverImpl.log("CHECKING: " + export.getExporter().getBundle() + ", " + export.getName()); //$NON-NLS-1$ //$NON-NLS-2$
			// Check if export matches
			if (imp.isSatisfiedBy(export) && imp.isNotAnUnresolvableWiring(export)) {
				int originalState = export.getExporter().getState();
				if (imp.isDynamic() && originalState != ResolverBundle.RESOLVED)
					continue; // Must not attempt to resolve an exporter when dynamic
				if (imp.getBundle() == export.getExporter() && !export.getExportPackageDescription().isRoot())
					continue; // Can't wire to our own re-export
				export.getExporter().addRef(imp.getBundle());
				if (result)
					continue;
				imp.setMatchingExport(export); // Wire the import to the export
				ResolverExport[] importerExps = null;
				if (imp.getBundle() != export.getExporter()) {
					// Save the exports of this package from the importer in case we need to add them back
					importerExps = imp.getBundle().getExports(imp.getName());
					for (int j = 0; j < importerExps.length; j++) {
						if (importerExps[j].getExportPackageDescription().isRoot() && !export.getExportPackageDescription().isRoot())
							continue exportsloop; // to prevent imports from getting wired to re-exports if we offer a root export
						if (importerExps[j].getExportPackageDescription().isRoot()) // do not drop reexports when import wins
							resolverExports.remove(importerExps[j]); // Import wins, remove export
					}
					// if in dev mode then allow a constraint to resolve to an unresolved bundle
					if ((originalState != ResolverBundle.RESOLVED && !resolveBundle(export.getExporter(), cycle) && !developmentMode) || export.isDropped()) {
						if (imp.getMatchingExport() != null && imp.getMatchingExport() != export) // has been resolved to some other export recursively
							return true;
						// add back the exports of this package from the importer
						for (int j = 0; j < importerExps.length; j++)
							resolverExports.put(importerExps[j].getName(), importerExps[j]);
						imp.setMatchingExport(null);
						continue; // Bundle hasn't resolved || export has not been selected and is unavailable
					}
				}
				// If the importer has become unresolvable then stop here
				if (!imp.getBundle().isResolvable())
					return false;
				// Check grouping dependencies
				if (checkImportConstraints(imp, imp.getMatchingExport(), cycle, importerExps) && imp.getMatchingExport() != null) {
					// Record any cyclic dependencies
					if (export != imp.getMatchingExport())
						export = imp.getMatchingExport();
					if (imp.getBundle() != export.getExporter())
						if (export.getExporter().getState() == ResolverBundle.RESOLVING) {
							// If the exporter is RESOLVING, we have a cyclic dependency
							if (!cycle.contains(imp.getBundle())) {
								cycle.add(imp.getBundle());
								if (DEBUG_CYCLES)
									ResolverImpl.log("import-package cycle: " + imp.getBundle() + " -> " + imp.getMatchingExport() + " from " + imp.getMatchingExport().getBundle()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							}
						}
					if (DEBUG_IMPORTS)
						ResolverImpl.log("Found match: " + export.getExporter() + ". Wiring " + imp.getBundle() + ":" + imp.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					result = true;
				} else if (!imp.getBundle().isResolvable()) {
					// If grouping has caused recursive calls to resolveImport, and the grouping has failed
					// then we need to catch that here, so we don't continue trying to wire here
					return false;
				}
				if (!result && imp.getMatchingExport() != null && imp.getMatchingExport() != export)
					return true; // Grouping has changed the wiring, so return here
			}
		}
		if (result)
			return true;
		if (checkReexportsFromRequires && resolveImportReprovide(imp, cycle))
			return true; // A reprovide satisfies imp
		if (imp.isOptional())
			return true; // If the import is optional then just return true

		return false;
	}

	// Check if the import can be resolved to a re-exported package (has no export object to match to)
	private boolean resolveImportReprovide(ResolverImport imp, ArrayList cycle) {
		String bsn = ((ImportPackageSpecification) imp.getVersionConstraint()).getBundleSymbolicName();
		// If no symbolic name specified then just return (since this is a
		// re-export an import not specifying a bsn will wire to the root)
		if (bsn == null)
			return false;
		if (DEBUG_IMPORTS)
			ResolverImpl.log("Checking reprovides: " + imp.getName()); //$NON-NLS-1$
		// Find bundle with specified bsn
		Object[] bundles = resolverBundles.get(bsn);
		for (int i = 0; i < bundles.length; i++)
			if (resolveBundle((ResolverBundle) bundles[i], cycle))
				if (resolveImportReprovide0(imp, (ResolverBundle) bundles[i], (ResolverBundle) bundles[i], cycle, new ArrayList(5)))
					return true;
		return false;
	}

	private boolean resolveImportReprovide0(ResolverImport imp, ResolverBundle reexporter, ResolverBundle rb, ArrayList cycle, ArrayList visited) {
		if (visited.contains(rb))
			return false; // make sure we don't endless recurse cycles
		visited.add(rb);
		BundleConstraint[] requires = rb.getRequires();
		for (int i = 0; i < requires.length; i++) {
			if (!((BundleSpecification) requires[i].getVersionConstraint()).isExported())
				continue; // Skip require if it doesn't re-export the packages
			// Check exports to see if we've found the root
			if (requires[i].getMatchingBundle() == null)
				continue;
			ResolverExport[] exports = requires[i].getMatchingBundle().getExports(imp.getName());
			for (int j = 0; j < exports.length; j++) {
				Map directives = exports[j].getExportPackageDescription().getDirectives();
				directives.remove(Constants.USES_DIRECTIVE);
				ExportPackageDescription epd = state.getFactory().createExportPackageDescription(exports[j].getName(), exports[j].getVersion(), directives, exports[j].getExportPackageDescription().getAttributes(), false, reexporter.getBundle());
				if (imp.getVersionConstraint().isSatisfiedBy(epd)) {
					// Create reexport and add to bundle and resolverExports
					if (DEBUG_IMPORTS)
						ResolverImpl.log(" - Creating re-export for reprovide: " + reexporter + ":" + epd.getName()); //$NON-NLS-1$ //$NON-NLS-2$
					ResolverExport re = new ResolverExport(reexporter, epd);
					reexporter.addExport(re);
					resolverExports.put(re.getName(), re);
					// Resolve import
					if (resolveImport(imp, false, cycle))
						return true;
				}
			}
			// Check requires of matching bundle (recurse down the chain)
			if (resolveImportReprovide0(imp, reexporter, requires[i].getMatchingBundle(), cycle, visited))
				return true;
		}
		return false;
	}

	// This method checks and resolves (if possible) grouping dependencies
	// Returns true, if the dependencies can be resolved, false otherwise
	private boolean checkImportConstraints(ResolverImport imp, ResolverExport exp, ArrayList cycle, ResolverExport[] importerExps) {
		if (DEBUG_GROUPING)
			ResolverImpl.log("  Checking grouping for " + imp.getBundle() + ":" + imp.getName() + " -> " + exp.getExporter() + ":" + exp.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		ResolverBundle importer = imp.getBundle();
		ResolverExport clash = groupingChecker.isConsistent(imp, exp);
		if (clash == null)
			return true;
		if (DEBUG_GROUPING)
			ResolverImpl.log("  * grouping clash with " + clash.getExporter() + ":" + clash.getName()); //$NON-NLS-1$ //$NON-NLS-2$
		// Try to rewire imp
		imp.addUnresolvableWiring(exp.getExporter());
		imp.setMatchingExport(null);
		if (resolveImport(imp, false, cycle))
			return true;
		if (imp.isDynamic())
			return false;
		// Rewiring of imp has failed so try to rewire clashing import
		imp.clearUnresolvableWirings();
		imp.setMatchingExport(exp);
		ResolverImport[] imports = importer.getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			if (imports[i].getMatchingExport() != null && imports[i].getMatchingExport().getName().equals(clash.getName())) {
				imports[i].addUnresolvableWiring(imports[i].getMatchingExport().getExporter());
				imports[i].setMatchingExport(null); // clear the conflicting wire
				// If the clashing import package was also exported then
				// we need to put the export back into resolverExports
				if (importerExps != null)
					resolverExports.put(importerExps);
			}
		}
		// Try to re-resolve the bundle
		if (resolveBundle(importer, cycle))
			return true;
		state.addResolverError(imp.getVersionConstraint().getBundle(), ResolverError.IMPORT_PACKAGE_USES_CONFLICT, imp.getVersionConstraint().toString(), imp.getVersionConstraint());
		return false;
	}

	// Move a bundle to UNRESOLVED
	private void setBundleUnresolved(ResolverBundle bundle, boolean removed, boolean isDevMode) {
		if (bundle.getState() == ResolverBundle.UNRESOLVED)
			return;
		if (bundle.getBundle().isResolved()) {
			resolverExports.remove(bundle.getExportPackages());
			if (removed)
				resolverGenerics.remove(bundle.getGenericCapabilities());
			bundle.initialize(false);
			if (!removed)
				resolverExports.put(bundle.getExportPackages());
		}
		if (!removed)
			unresolvedBundles.add(bundle);
		if (!isDevMode)
			bundle.detachAllFragments();
		bundle.setState(ResolverBundle.UNRESOLVED);
	}

	// Move a bundle to RESOLVED
	private void setBundleResolved(ResolverBundle bundle) {
		if (bundle.getState() == ResolverBundle.RESOLVED)
			return;
		unresolvedBundles.remove(bundle);
		bundle.setState(ResolverBundle.RESOLVED);
	}

	// Move a bundle to RESOLVING
	private void setBundleResolving(ResolverBundle bundle) {
		if (bundle.getState() == ResolverBundle.RESOLVING)
			return;
		unresolvedBundles.remove(bundle);
		bundle.setState(ResolverBundle.RESOLVING);
	}

	// Resolves the bundles in the State
	private void stateResolveBundles(ResolverBundle[] resolvedBundles) {
		for (int i = 0; i < resolvedBundles.length; i++)
			if (!resolvedBundles[i].getBundle().isResolved())
				stateResolveBundle(resolvedBundles[i]);
	}

	private void stateResolveConstraints(ResolverBundle rb) {
		ResolverImport[] imports = rb.getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			ResolverExport export = imports[i].getMatchingExport();
			BaseDescription supplier = export == null ? null : export.getExportPackageDescription();
			state.resolveConstraint(imports[i].getVersionConstraint(), supplier);
		}
		BundleConstraint[] requires = rb.getRequires();
		for (int i = 0; i < requires.length; i++) {
			ResolverBundle bundle = requires[i].getMatchingBundle();
			BaseDescription supplier = bundle == null ? null : bundle.getBundle();
			state.resolveConstraint(requires[i].getVersionConstraint(), supplier);
		}
		GenericConstraint[] genericRequires = rb.getGenericRequires();
		for (int i = 0; i < genericRequires.length; i++) {
			GenericCapability[] matchingCapabilities = genericRequires[i].getMatchingCapabilities();
			if (matchingCapabilities == null)
				state.resolveConstraint(genericRequires[i].getVersionConstraint(), null);
			else
				for (int j = 0; j < matchingCapabilities.length; j++)
					state.resolveConstraint(genericRequires[i].getVersionConstraint(), matchingCapabilities[j].getBaseDescription());
		}
	}

	private void stateResolveFragConstraints(ResolverBundle rb) {
		ResolverBundle host = rb.getHost().getMatchingBundle();
		ImportPackageSpecification[] imports = rb.getBundle().getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			ResolverImport hostImport = host.getImport(imports[i].getName());
			ResolverExport export = hostImport == null ? null : hostImport.getMatchingExport();
			BaseDescription supplier = export == null ? null : export.getExportPackageDescription();
			state.resolveConstraint(imports[i], supplier);
		}
		BundleSpecification[] requires = rb.getBundle().getRequiredBundles();
		for (int i = 0; i < requires.length; i++) {
			BundleConstraint hostRequire = host.getRequire(requires[i].getName());
			ResolverBundle bundle = hostRequire == null ? null : hostRequire.getMatchingBundle();
			BaseDescription supplier = bundle == null ? null : bundle.getBundle();
			state.resolveConstraint(requires[i], supplier);
		}
	}

	private void stateResolveBundle(ResolverBundle rb) {
		// if in dev mode then we want to tell the state about the constraints we were able to resolve
		if (!rb.isResolved() && !developmentMode)
			return;
		// Gather selected exports
		ResolverExport[] exports = rb.getSelectedExports();
		ArrayList selectedExports = new ArrayList(exports.length);
		for (int i = 0; i < exports.length; i++) {
			selectedExports.add(exports[i].getExportPackageDescription());
		}
		ExportPackageDescription[] selectedExportsArray = (ExportPackageDescription[]) selectedExports.toArray(new ExportPackageDescription[selectedExports.size()]);

		// Gather exports that have been wired to
		ResolverImport[] imports = rb.getImportPackages();
		ArrayList exportsWiredTo = new ArrayList(imports.length);
		for (int i = 0; i < imports.length; i++) {
			if (imports[i].getMatchingExport() != null) {
				exportsWiredTo.add(imports[i].getMatchingExport().getExportPackageDescription());
			}
		}
		ExportPackageDescription[] exportsWiredToArray = (ExportPackageDescription[]) exportsWiredTo.toArray(new ExportPackageDescription[exportsWiredTo.size()]);

		// Gather bundles that have been wired to
		BundleConstraint[] requires = rb.getRequires();
		ArrayList bundlesWiredTo = new ArrayList(requires.length);
		for (int i = 0; i < requires.length; i++)
			if (requires[i].getMatchingBundle() != null)
				bundlesWiredTo.add(requires[i].getMatchingBundle().getBundle());
		BundleDescription[] bundlesWiredToArray = (BundleDescription[]) bundlesWiredTo.toArray(new BundleDescription[bundlesWiredTo.size()]);

		BundleDescription[] hostBundles = null;
		if (rb.isFragment()) {
			ResolverBundle[] matchingBundles = rb.getHost().getMatchingBundles();
			if (matchingBundles != null && matchingBundles.length > 0) {
				hostBundles = new BundleDescription[matchingBundles.length];
				for (int i = 0; i < matchingBundles.length; i++) {
					hostBundles[i] = matchingBundles[i].getBundle();
					if (rb.isNewFragmentExports() && hostBundles[i].isResolved()) {
						// update the host's set of selected exports
						ResolverExport[] hostExports = matchingBundles[i].getSelectedExports();
						ExportPackageDescription[] hostExportsArray = new ExportPackageDescription[hostExports.length];
						for (int j = 0; j < hostExports.length; j++)
							hostExportsArray[j] = hostExports[j].getExportPackageDescription();
						state.resolveBundle(hostBundles[i], true, null, hostExportsArray, hostBundles[i].getResolvedRequires(), hostBundles[i].getResolvedImports());
					}
				}
			}
		}

		// Resolve the bundle in the state
		state.resolveBundle(rb.getBundle(), rb.isResolved(), hostBundles, selectedExportsArray, bundlesWiredToArray, exportsWiredToArray);
	}

	// Resolve dynamic import
	public synchronized ExportPackageDescription resolveDynamicImport(BundleDescription importingBundle, String requestedPackage) {
		if (state == null)
			throw new IllegalStateException("RESOLVER_NO_STATE"); //$NON-NLS-1$

		// Make sure the resolver is initialized
		if (!initialized)
			initialize();

		ResolverBundle rb = (ResolverBundle) bundleMapping.get(importingBundle);
		if (rb.getExport(requestedPackage) != null)
			return null; // do not allow dynamic wires for packages which this bundle exports
		ResolverImport[] resolverImports = rb.getImportPackages();
		// Check through the ResolverImports of this bundle.
		// If there is a matching one then pass it into resolveImport()
		boolean found = false;
		for (int j = 0; j < resolverImports.length; j++) {
			// Make sure it is a dynamic import
			if (!resolverImports[j].isDynamic())
				continue;
			String importName = resolverImports[j].getName();
			// If the import uses a wildcard, then temporarily replace this with the requested package
			if (importName.equals("*") || //$NON-NLS-1$
					(importName.endsWith(".*") && requestedPackage.startsWith(importName.substring(0, importName.length() - 2)))) { //$NON-NLS-1$
				resolverImports[j].setName(requestedPackage);
			}
			// Resolve the import
			if (requestedPackage.equals(resolverImports[j].getName())) {
				found = true;
				if (resolveImport(resolverImports[j], true, new ArrayList())) {
					// If the import resolved then return it's matching export
					resolverImports[j].setName(null);
					if (DEBUG_IMPORTS)
						ResolverImpl.log("Resolved dynamic import: " + rb + ":" + resolverImports[j].getName() + " -> " + resolverImports[j].getMatchingExport().getExporter() + ":" + requestedPackage); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					ExportPackageDescription matchingExport = resolverImports[j].getMatchingExport().getExportPackageDescription();
					// If it is a wildcard import then clear the wire, so other
					// exported packages can be found for it
					if (importName.endsWith("*")) //$NON-NLS-1$
						resolverImports[j].setMatchingExport(null);
					return matchingExport;
				}
			}
			// Reset the import package name
			resolverImports[j].setName(null);
		}
		// this is to support adding dynamic imports on the fly.
		if (!found) {
			Map directives = new HashMap(1);
			directives.put(Constants.RESOLUTION_DIRECTIVE, ImportPackageSpecification.RESOLUTION_DYNAMIC);
			ImportPackageSpecification packageSpec = state.getFactory().createImportPackageSpecification(requestedPackage, null, null, null, directives, null, importingBundle);
			ResolverImport newImport = new ResolverImport(rb, packageSpec);
			if (resolveImport(newImport, true, new ArrayList()))
				return newImport.getMatchingExport().getExportPackageDescription();
		}
		if (DEBUG || DEBUG_IMPORTS)
			ResolverImpl.log("Failed to resolve dynamic import: " + requestedPackage); //$NON-NLS-1$
		return null; // Couldn't resolve the import, so return null
	}

	public void bundleAdded(BundleDescription bundle) {
		if (!initialized)
			return;

		boolean alreadyThere = false;
		for (int i = 0; i < unresolvedBundles.size(); i++) {
			ResolverBundle rb = (ResolverBundle) unresolvedBundles.get(i);
			if (rb.getBundle() == bundle) {
				alreadyThere = true;
			}
		}
		if (!alreadyThere) {
			ResolverBundle rb = new ResolverBundle(bundle, this);
			bundleMapping.put(bundle, rb);
			unresolvedBundles.add(rb);
			resolverExports.put(rb.getExportPackages());
			resolverBundles.put(rb.getName(), rb);
			resolverGenerics.put(rb.getGenericCapabilities());
		}
	}

	public void bundleRemoved(BundleDescription bundle, boolean pending) {
		// check if there are any dependants
		if (pending)
			removalPending.put(new Long(bundle.getBundleId()), bundle);
		if (!initialized)
			return;
		ResolverBundle rb = (ResolverBundle) bundleMapping.get(bundle);
		if (rb == null)
			return;

		if (!pending) {
			bundleMapping.remove(bundle);
			groupingChecker.removeAllExportConstraints(rb);
		}
		if (!pending || !bundle.isResolved()) {
			resolverExports.remove(rb.getExportPackages());
			resolverBundles.remove(rb);
			resolverGenerics.remove(rb.getGenericCapabilities());
		}
		unresolvedBundles.remove(rb);
	}

	private void unresolveBundle(ResolverBundle bundle, boolean removed) {
		if (bundle == null)
			return;
		// check the removed list if unresolving then remove from the removed list
		Object[] removedBundles = removalPending.remove(new Long(bundle.getBundle().getBundleId()));
		for (int i = 0; i < removedBundles.length; i++) {
			ResolverBundle re = (ResolverBundle) bundleMapping.get(removedBundles[i]);
			unresolveBundle(re, true);
			state.removeBundleComplete((BundleDescription) removedBundles[i]);
			resolverExports.remove(re.getExportPackages());
			resolverBundles.remove(re);
			resolverGenerics.remove(re.getGenericCapabilities());
			bundleMapping.remove(removedBundles[i]);
			groupingChecker.removeAllExportConstraints(re);
			// the bundle is removed
			if (removedBundles[i] == bundle.getBundle())
				removed = true;
		}

		if (!bundle.getBundle().isResolved() && !developmentMode)
			return;
		// if not removed then add to the list of unresolvedBundles,
		// passing false for devmode because we need all fragments detached
		setBundleUnresolved(bundle, removed, false);
		// Get bundles dependent on 'bundle'
		BundleDescription[] dependents = bundle.getBundle().getDependents();
		state.resolveBundle(bundle.getBundle(), false, null, null, null, null);
		// Unresolve dependents of 'bundle'
		for (int i = 0; i < dependents.length; i++)
			unresolveBundle((ResolverBundle) bundleMapping.get(dependents[i]), false);
	}

	public void bundleUpdated(BundleDescription newDescription, BundleDescription existingDescription, boolean pending) {
		bundleRemoved(existingDescription, pending);
		bundleAdded(newDescription);
	}

	public void flush() {
		resolverExports = null;
		resolverBundles = null;
		resolverGenerics = null;
		unresolvedBundles = null;
		bundleMapping = null;
		Object[] removed = removalPending.getAllValues();
		for (int i = 0; i < removed.length; i++)
			state.removeBundleComplete((BundleDescription) removed[i]);
		removalPending.clear();
		initialized = false;
	}

	public State getState() {
		return state;
	}

	public void setState(State newState) {
		state = newState;
		flush();
	}

	private void setDebugOptions() {
		FrameworkDebugOptions options = FrameworkDebugOptions.getDefault();
		// may be null if debugging is not enabled
		if (options == null)
			return;
		DEBUG = options.getBooleanOption(OPTION_DEBUG, false);
		DEBUG_WIRING = options.getBooleanOption(OPTION_WIRING, false);
		DEBUG_IMPORTS = options.getBooleanOption(OPTION_IMPORTS, false);
		DEBUG_REQUIRES = options.getBooleanOption(OPTION_REQUIRES, false);
		DEBUG_GENERICS = options.getBooleanOption(OPTION_GENERICS, false);
		DEBUG_GROUPING = options.getBooleanOption(OPTION_GROUPING, false);
		DEBUG_CYCLES = options.getBooleanOption(OPTION_CYCLES, false);
	}

	// LOGGING METHODS
	private void printWirings() {
		ResolverImpl.log("****** Result Wirings ******"); //$NON-NLS-1$
		Object[] bundles = resolverBundles.getAllValues();
		for (int j = 0; j < bundles.length; j++) {
			ResolverBundle rb = (ResolverBundle) bundles[j];
			if (rb.getBundle().isResolved()) {
				continue;
			}
			ResolverImpl.log("    * WIRING for " + rb); //$NON-NLS-1$
			// Require bundles
			BundleConstraint[] requireBundles = rb.getRequires();
			if (requireBundles.length == 0) {
				ResolverImpl.log("        (r) no requires"); //$NON-NLS-1$
			} else {
				for (int i = 0; i < requireBundles.length; i++) {
					if (requireBundles[i].getMatchingBundle() == null) {
						ResolverImpl.log("        (r) " + rb.getBundle() + " -> NULL!!!"); //$NON-NLS-1$ //$NON-NLS-2$
					} else {
						ResolverImpl.log("        (r) " + rb.getBundle() + " -> " + requireBundles[i].getMatchingBundle()); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			}
			// Hosts
			BundleConstraint hostSpec = rb.getHost();
			if (hostSpec != null) {
				ResolverBundle[] hosts = hostSpec.getMatchingBundles();
				if (hosts != null)
					for (int i = 0; i < hosts.length; i++) {
						ResolverImpl.log("        (h) " + rb.getBundle() + " -> " + hosts[i].getBundle()); //$NON-NLS-1$ //$NON-NLS-2$
					}
			}
			// Imports
			ResolverImport[] imports = rb.getImportPackages();
			if (imports.length == 0) {
				ResolverImpl.log("        (w) no imports"); //$NON-NLS-1$
				continue;
			}
			for (int i = 0; i < imports.length; i++) {
				if (imports[i].isDynamic() && imports[i].getMatchingExport() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> DYNAMIC"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else if (imports[i].isOptional() && imports[i].getMatchingExport() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> OPTIONAL (could not be wired)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else if (imports[i].getMatchingExport() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> NULL!!!"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							imports[i].getMatchingExport().getExporter() + ":" + imports[i].getMatchingExport().getName()); //$NON-NLS-1$
				}
			}
		}
	}

	static void log(String message) {
		Debug.println(message);
	}

	VersionHashMap getResolverExports() {
		return resolverExports;
	}

	public void setSelectionPolicy(Comparator selectionPolicy) {
		this.selectionPolicy = selectionPolicy;
	}

	public Comparator getSelectionPolicy() {
		return selectionPolicy;
	}
}

/*******************************************************************************
 * Copyright (c) 2004, 2007 IBM Corporation and others. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.osgi.internal.module;

import java.util.*;
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.debug.FrameworkDebugOptions;
import org.eclipse.osgi.internal.resolver.BundleDescriptionImpl;
import org.eclipse.osgi.internal.resolver.ExportPackageDescriptionImpl;
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
		if (req.getSelectedSupplier() != null)
			return;
		ResolverBundle matchingBundle = (ResolverBundle) bundleMapping.get(req.getVersionConstraint().getSupplier());
		req.addPossibleSupplier(matchingBundle);
		if (matchingBundle == null && !req.isOptional()) {
			System.err.println("Could not find matching bundle for " + req.getVersionConstraint()); //$NON-NLS-1$
			// TODO log error!!
		}
		if (matchingBundle != null) {
			rewireBundle(matchingBundle, visited);
		}
	}

	private void rewireImport(ResolverImport imp, ArrayList visited) {
		if (imp.isDynamic() || imp.getSelectedSupplier() != null)
			return;
		// Re-wire 'imp'
		ResolverExport matchingExport = null;
		ExportPackageDescription importSupplier = (ExportPackageDescription) imp.getVersionConstraint().getSupplier();
		ResolverBundle exporter = importSupplier == null ? null : (ResolverBundle) bundleMapping.get(importSupplier.getExporter());
		Object[] matches = resolverExports.get(imp.getName());
		for (int j = 0; j < matches.length; j++) {
			ResolverExport export = (ResolverExport) matches[j];
			if (export.getExporter() == exporter && importSupplier == export.getExportPackageDescription()) {
				matchingExport = export;
				break;
			}
		}
		imp.addPossibleSupplier(matchingExport);
		// Check if we wired to a reprovided package (in which case the ResolverExport doesn't exist)
		if (matchingExport == null && exporter != null) {
			ResolverExport reprovidedExport = new ResolverExport(exporter, importSupplier);
			if (exporter.getExport(imp.getName()) == null) {
				exporter.addExport(reprovidedExport);
				resolverExports.put(reprovidedExport.getName(), reprovidedExport);
			}
			imp.addPossibleSupplier(reprovidedExport);
		}
		// If we still have a null wire and it's not optional, then we have an error
		if (imp.getSelectedSupplier() == null && !imp.isOptional()) {
			System.err.println("Could not find matching export for " + imp.getVersionConstraint()); //$NON-NLS-1$
			// TODO log error!!
		}
		if (imp.getSelectedSupplier() != null) {
			rewireBundle(((ResolverExport) imp.getSelectedSupplier()).getExporter(), visited);
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
				// using matchCase here in case of duplicate case invarient keys (bug 180817)
				if (filter.matchCase(platformProperties[i]))
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
		reRefresh = addDevConstraints(reRefresh);
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

	private BundleDescription[] addDevConstraints(BundleDescription[] reRefresh) {
		if (!developmentMode)
			return reRefresh; // we don't care about this unless we are in development mode
		// when in develoment mode we need to reRefresh hosts  of unresolved fragments that add new constraints 
		// and reRefresh and unresolved bundles that have dependents
		HashSet additionalRefresh = new HashSet();
		ResolverBundle[] unresolved = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
		for (int i = 0; i < unresolved.length; i++) {
			addUnresolvedWithDependents(unresolved[i], additionalRefresh);
			addHostsFromFragmentConstraints(unresolved[i], additionalRefresh);
		}
		if (additionalRefresh.size() == 0)
			return reRefresh; // no new bundles found to refresh
		// add the original reRefresh bundles to the set
		if (reRefresh != null)
			for (int i = 0; i < reRefresh.length; i++)
				additionalRefresh.add(reRefresh[i]);
		return (BundleDescription[]) additionalRefresh.toArray(new BundleDescription[additionalRefresh.size()]);
	}

	private void addUnresolvedWithDependents(ResolverBundle unresolved, HashSet additionalRefresh) {
		BundleDescription[] dependents = unresolved.getBundle().getDependents();
		if (dependents.length > 0)
			additionalRefresh.add(unresolved.getBundle());
	}

	private void addHostsFromFragmentConstraints(ResolverBundle unresolved, Set additionalRefresh) {
		if (!unresolved.isFragment())
			return;
		ImportPackageSpecification[] newImports = unresolved.getBundle().getImportPackages();
		BundleSpecification[] newRequires = unresolved.getBundle().getRequiredBundles();
		if (newImports.length == 0 && newRequires.length == 0)
			return; // the fragment does not have its own constraints
		BundleConstraint hostConstraint = unresolved.getHost();
		Object[] hosts = resolverBundles.get(hostConstraint.getVersionConstraint().getName());
		for (int j = 0; j < hosts.length; j++)
			if (hostConstraint.isSatisfiedBy((ResolverBundle) hosts[j]) && ((ResolverBundle) hosts[j]).isResolved())
				// we found a host that is resolved;
				// add it to the set of bundle to refresh so we can ensure this fragment is allowed to resolve
				additionalRefresh.add(((ResolverBundle) hosts[j]).getBundle());

	}

	private void resolveOptionalConstraints(ResolverBundle[] bundles) {
		for (int i = 0; i < bundles.length; i++) {
			if (bundles[i] != null)
				resolveOptionalConstraints(bundles[i]);
		}
	}

	// TODO this does not do proper uses constraint verification.
	private void resolveOptionalConstraints(ResolverBundle bundle) {
		BundleConstraint[] requires = bundle.getRequires();
		ArrayList cycle = new ArrayList();
		boolean resolvedOptional = false;
		for (int i = 0; i < requires.length; i++)
			if (requires[i].isOptional() && requires[i].getSelectedSupplier() == null) {
				cycle.clear();
				resolveRequire(requires[i], cycle);
				if (requires[i].getSelectedSupplier() != null)
					resolvedOptional = true;
			}
		ResolverImport[] imports = bundle.getImportPackages();
		for (int i = 0; i < imports.length; i++)
			if (imports[i].isOptional() && imports[i].getSelectedSupplier() == null) {
				cycle.clear();
				resolveImport(imports[i], cycle);
				if (imports[i].getSelectedSupplier() != null)
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
		}
		resolveBundles0(bundles, platformProperties, rejectedSingletons);
		if (DEBUG_WIRING)
			printWirings();
		// set the resolved status of the bundles in the State
		stateResolveBundles(bundles);
	}

	private void resolveBundles0(ResolverBundle[] bundles, Dictionary[] platformProperties, ArrayList rejectedSingletons) {
		if (developmentMode) 
			// need to sort bundles to keep consistent order for fragment attachment (bug 174930)
			Arrays.sort(bundles);
		// First attach all fragments to the matching hosts
		for (int i = 0; i < bundles.length; i++)
			attachFragment(bundles[i], rejectedSingletons);

		// Lists of cyclic dependencies recording during resolving
		ArrayList cycle = new ArrayList(1); // start small
		// Attempt to resolve all unresolved bundles
		for (int i = 0; i < bundles.length; i++) {
			if (DEBUG)
				ResolverImpl.log("** RESOLVING " + bundles[i] + " **"); //$NON-NLS-1$ //$NON-NLS-2$
			cycle.clear();
			resolveBundle(bundles[i], cycle);
			// Check for any bundles involved in a cycle.
			// if any bundles in the cycle are not resolved then we need to resolve the resolvable ones
			checkCycle(cycle);
		}
		// Resolve all fragments that are still attached to at least one host.
		if (unresolvedBundles.size() > 0) {
			ResolverBundle[] unresolved = (ResolverBundle[]) unresolvedBundles.toArray(new ResolverBundle[unresolvedBundles.size()]);
			for (int i = 0; i < unresolved.length; i++)
				resolveFragment(unresolved[i]);
		}
		checkUsesConstraints(bundles, platformProperties, rejectedSingletons);
	}

	private void checkUsesConstraints(ResolverBundle[] bundles, Dictionary[] platformProperties, ArrayList rejectedSingletons) {
		ResolverConstraint[] multipleSuppliers = getMultipleSuppliers(bundles);
		ArrayList conflictingConstraints = findBestCombination(bundles, multipleSuppliers);
		Set conflictedBundles = null;
		if (conflictingConstraints != null) {
			for (Iterator conflicts = conflictingConstraints.iterator(); conflicts.hasNext();) {
				ResolverConstraint conflict = (ResolverConstraint) conflicts.next();
				if (conflict.isOptional()) {
					conflict.clearPossibleSuppliers();
					continue;
				}
				conflictedBundles = new HashSet(conflictingConstraints.size());
				ResolverBundle conflictedBundle;
				if (conflict.isFromFragment())
					conflictedBundle = (ResolverBundle) bundleMapping.get(conflict.getVersionConstraint().getBundle());
				else
					conflictedBundle = conflict.getBundle();
				if (conflictedBundle != null) {
					conflictedBundles.add(conflictedBundle);
					int type = conflict instanceof ResolverImport ? ResolverError.IMPORT_PACKAGE_USES_CONFLICT : ResolverError.REQUIRE_BUNDLE_USES_CONFLICT;
					state.addResolverError(conflictedBundle.getBundle(), type, conflict.getVersionConstraint().toString(), conflict.getVersionConstraint());
					conflictedBundle.setResolvable(false);
					conflictedBundle.clearRefs();
					setBundleUnresolved(conflictedBundle, false, developmentMode);
				}
			}
			if (conflictedBundles != null && conflictedBundles.size() > 0) {
				ArrayList remainingUnresolved = new ArrayList();
				for (int i = 0; i < bundles.length; i++) {
					if (!conflictedBundles.contains(bundles[i])) {
						setBundleUnresolved(bundles[i], false, developmentMode);
						remainingUnresolved.add(bundles[i]);
					}
				}
				resolveBundles0((ResolverBundle[]) remainingUnresolved.toArray(new ResolverBundle[remainingUnresolved.size()]), platformProperties, rejectedSingletons);
			}
		}
	}

	private ArrayList findBestCombination(ResolverBundle[] bundles, ResolverConstraint[] multipleSuppliers) {
		int[] bestCombination = new int[multipleSuppliers.length];
		ArrayList conflictingBundles = null;
		if (multipleSuppliers.length > 0) {
			conflictingBundles = findBestCombination(bundles, multipleSuppliers, bestCombination);
			for (int i = 0; i < bestCombination.length; i++)
				multipleSuppliers[i].setSelectedSupplier(bestCombination[i]);
		} else {
			conflictingBundles = getConflicts(bundles);
		}
		// do not need to keep uses data in memory
		groupingChecker.clear();
		return conflictingBundles;
	}

	private void getCombination(ResolverConstraint[] multipleSuppliers, int[] combination) {
		for (int i = 0; i < combination.length; i++)
			combination[i] = multipleSuppliers[i].getSelectedSupplierIndex();
	}

	private ArrayList findBestCombination(ResolverBundle[] bundles, ResolverConstraint[] multipleSuppliers, int[] bestCombination) {
		// first tryout all zeros
		ArrayList bestConflicts = getConflicts(bundles);
		if (bestConflicts == null)
			return null; // the first selected have no conflicts; return without iterating over all combinations
		// now iterate over every possible combination until either zero conflicts are found 
		// or we have run out of combinations
		// if all combinations are tried then return the combination with the lowest number of conflicts
		int bestConflictCount = getConflictCount(bestConflicts);
		while (bestConflictCount != 0 && getNextCombination(multipleSuppliers)) {
			ArrayList conflicts = getConflicts(bundles);
			int conflictCount = getConflictCount(conflicts);
			if (conflictCount < bestConflictCount) {
				bestConflictCount = conflictCount;
				bestConflicts = conflicts;
				getCombination(multipleSuppliers, bestCombination);
			}
		}
		return bestConflicts;
	}

	private boolean getNextCombination(ResolverConstraint[] multipleSuppliers) {
		if (multipleSuppliers[0].selectNextSupplier())
			return true; // the current slot has a next supplier
		multipleSuppliers[0].setSelectedSupplier(0); // reset first slot
		int current = 1;
		while (current < multipleSuppliers.length) {
			if (multipleSuppliers[current].selectNextSupplier())
				return true;
			multipleSuppliers[current].setSelectedSupplier(0); // reset the current slot
			current++; // move to the next slot
		}
		return false;
	}

	// only count non-optional conflicts
	private int getConflictCount(ArrayList conflicts) {
		if (conflicts == null || conflicts.size() == 0)
			return 0;
		int result = 0;
		for (Iterator iConflicts = conflicts.iterator(); iConflicts.hasNext();)
			if (!((ResolverConstraint) iConflicts.next()).isOptional())
				result += 1;
		return result;
	}

	private ArrayList getConflicts(ResolverBundle[] bundles) {
		groupingChecker.clear();
		ArrayList conflicts = null;
		bundlesLoop: for (int i = 0; i < bundles.length; i++) {
			BundleConstraint[] requires = bundles[i].getRequires();
			for (int j = 0; j < requires.length; j++) {
				ResolverBundle selectedSupplier = (ResolverBundle) requires[j].getSelectedSupplier();
				ResolverExport conflict = selectedSupplier == null ? null : groupingChecker.isConsistent(bundles[i], selectedSupplier);
				if (conflict != null) {
					if (conflicts == null)
						conflicts = new ArrayList(1);
					conflicts.add(requires[j]);
					// continue on for optonal conflicts because we don't count them
					if (!requires[j].isOptional())
						continue bundlesLoop;
				}
			}
			ResolverImport[] imports = bundles[i].getImportPackages();
			for (int j = 0; j < imports.length; j++) {
				ResolverExport selectedSupplier = (ResolverExport) imports[j].getSelectedSupplier();
				ResolverExport conflict = selectedSupplier == null ? null : groupingChecker.isConsistent(bundles[i], selectedSupplier);
				if (conflict != null) {
					if (conflicts == null)
						conflicts = new ArrayList(1);
					conflicts.add(imports[j]);
					// continue on for optional conflicts because we don't count them
					if (!imports[j].isOptional())
						continue bundlesLoop;
				}
			}
		}
		return conflicts;
	}

	// get a list of resolver constraints that have multiple suppliers
	private ResolverConstraint[] getMultipleSuppliers(ResolverBundle[] bundles) {
		ArrayList multipleSuppliers = new ArrayList(1);
		for (int i = 0; i < bundles.length; i++) {
			BundleConstraint[] requires = bundles[i].getRequires();
			for (int j = 0; j < requires.length; j++)
				if (requires[j].getNumPossibleSuppliers() > 1)
					multipleSuppliers.add(requires[j]);
			ResolverImport[] imports = bundles[i].getImportPackages();
			for (int j = 0; j < imports.length; j++) {
				if (imports[j].getNumPossibleSuppliers() > 1) {
					Integer eeProfile = (Integer) ((ResolverExport) imports[j].getSelectedSupplier()).getExportPackageDescription().getDirective(ExportPackageDescriptionImpl.EQUINOX_EE);
					if (eeProfile.intValue() < 0) {
						// this is a normal package; always add it
						multipleSuppliers.add(imports[j]);
					} else {
						// this is a system bunde export
						// If other exporters of this package also require the system bundle
						// then this package does not need to be added to the mix
						// this is an optimization for bundles like org.eclipse.xerces
						// that export lots of packages also exported by the system bundle on J2SE 1.4
						VersionSupplier[] suppliers = imports[j].getPossibleSuppliers();
						for (int suppliersIndex = 1; suppliersIndex < suppliers.length; suppliersIndex++) {
							Integer ee = (Integer) ((ResolverExport) suppliers[suppliersIndex]).getExportPackageDescription().getDirective(ExportPackageDescriptionImpl.EQUINOX_EE);
							if (ee.intValue() >= 0)
								continue;
							if (((ResolverExport) suppliers[suppliersIndex]).getExporter().getRequire(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME) == null)
								if (((ResolverExport) suppliers[suppliersIndex]).getExporter().getRequire(Constants.SYSTEM_BUNDLE_SYMBOLICNAME) == null) {
									multipleSuppliers.add(imports[j]);
									break;
								}
						}
					}
				}
			}
		}
		return (ResolverConstraint[]) multipleSuppliers.toArray(new ResolverConstraint[multipleSuppliers.size()]);
	}

	private void checkCycle(ArrayList cycle) {
		int cycleSize = cycle.size();
		if (cycleSize == 0)
			return;
		for (int i = cycleSize - 1; i >= 0; i--) {
			ResolverBundle cycleBundle = (ResolverBundle) cycle.get(i);
			if (!cycleBundle.isResolvable())
				cycle.remove(i); // remove this from the list of bundles that need reresolved
			// Check that we haven't wired to any dropped exports
			ResolverImport[] imports = cycleBundle.getImportPackages();
			for (int j = 0; j < imports.length; j++) {
				// check for dropped exports
				while (imports[j].getSelectedSupplier() != null) {
					ResolverExport importSupplier = (ResolverExport) imports[j].getSelectedSupplier();
					if (importSupplier.isDropped())
						imports[j].selectNextSupplier();
					else
						break;
				}
				if (!imports[j].isDynamic() && !imports[j].isOptional() && imports[j].getSelectedSupplier() == null) {
					cycleBundle.setResolvable(false);
					cycleBundle.clearRefs();
					state.addResolverError(imports[j].getVersionConstraint().getBundle(), ResolverError.MISSING_IMPORT_PACKAGE, imports[j].getVersionConstraint().toString(), imports[j].getVersionConstraint());
					cycle.remove(i);
				}
			}
		}
		boolean reresolveCycle = cycle.size() != cycleSize; //we removed an unresolvable bundle; must reresolve remaining cycle
		if (reresolveCycle) {
			for (int i = 0; i < cycle.size(); i++) {
				ResolverBundle cycleBundle = (ResolverBundle) cycle.get(i);
				cycleBundle.clearWires();
				cycleBundle.clearRefs();
			}
			ArrayList innerCycle = new ArrayList(cycle.size());
			for (int i = 0; i < cycle.size(); i++)
				resolveBundle((ResolverBundle) cycle.get(i), innerCycle);
			checkCycle(innerCycle);
		} else {
			for (int i = 0; i < cycle.size(); i++) {
				if (DEBUG || DEBUG_CYCLES)
					ResolverImpl.log("Pushing " + cycle.get(i) + " to RESOLVED"); //$NON-NLS-1$ //$NON-NLS-2$
				setBundleResolved((ResolverBundle) cycle.get(i));
			}
		}
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
		if (fragment.getHost().getNumPossibleSuppliers() > 0)
			if (!developmentMode || state.getResolverErrors(fragment.getBundle()).length == 0)
				setBundleResolved(fragment);
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
			bundle.clearWires();
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
					if (!developmentMode) {
						// fail fast; otherwise we want to attempt to resolver other constraints in dev mode
						failed = true;
						break;
					}
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
					if (!developmentMode) {
						// fail fast; otherwise we want to attempt to resolver other constraints in dev mode
						failed = true;
						break;
					}
				}
			}
		}

		if (!failed) {
			// Iterate thru imports of 'bundle' trying to find matching exports.
			ResolverImport[] imports = bundle.getImportPackages();
			for (int i = 0; i < imports.length; i++) {
				// Only resolve non-dynamic imports here
				if (!imports[i].isDynamic() && !resolveImport(imports[i], cycle)) {
					if (DEBUG || DEBUG_IMPORTS)
						ResolverImpl.log("** IMPORT " + imports[i].getName() + "[" + imports[i].getBundleDescription() + "] failed to resolve"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					// If the import has failed to resolve and it is from a fragment, then remove the fragment from the host
					state.addResolverError(imports[i].getVersionConstraint().getBundle(), ResolverError.MISSING_IMPORT_PACKAGE, imports[i].getVersionConstraint().toString(), imports[i].getVersionConstraint());
					if (imports[i].isFromFragment()) {
						if (!developmentMode) // only detach fragments when not in devmode
							resolverExports.remove(bundle.detachFragment((ResolverBundle) bundleMapping.get(imports[i].getVersionConstraint().getBundle()), imports[i]));
						continue;
					}
					if (!developmentMode) {
						// fail fast; otherwise we want to attempt to resolver other constraints in dev mode
						failed = true;
						break;
					}
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
				VersionSupplier[] capabilityHosts = capability.isFromFragment() ? capability.getResolverBundle().getHost().getPossibleSuppliers() : new ResolverBundle[] {capability.getResolverBundle()};
				boolean foundResolvedMatch = false;
				for (int j = 0; capabilityHosts != null && j < capabilityHosts.length; j++) {
					ResolverBundle capabilitySupplier = (ResolverBundle) capabilityHosts[j];
					if (capabilitySupplier == constraint.getBundle()) {
						// the capability is from a fragment attached to this host do not recursively resolve the host again
						foundResolvedMatch = true;
						continue;
					}
					// if in dev mode then allow a constraint to resolve to an unresolved bundle
					if (capabilitySupplier.getState() == ResolverBundle.RESOLVED || (resolveBundle(capabilitySupplier, cycle) || developmentMode)) {
						foundResolvedMatch |= !capability.isFromFragment() ? true : capability.getResolverBundle().getHost().getPossibleSuppliers() != null;
						// Check cyclic dependencies
						if (capabilitySupplier.getState() == ResolverBundle.RESOLVING)
							if (!cycle.contains(capabilitySupplier))
								cycle.add(capabilitySupplier);
					}
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
		if (req.getSelectedSupplier() != null) {
			// Check for unrecorded cyclic dependency
			if (!cycle.contains(req.getBundle())) {
				cycle.add(req.getBundle());
				if (DEBUG_CYCLES)
					ResolverImpl.log("require-bundle cycle: " + req.getBundle() + " -> " + req.getSelectedSupplier()); //$NON-NLS-1$ //$NON-NLS-2$
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
				// first add the possible supplier; this is done before resolving the supplier bundle to prevent endless cycle loops.
				req.addPossibleSupplier(bundle);
				if (req.getBundle() != bundle) {
					// if in dev mode then allow a constraint to resolve to an unresolved bundle
					if (bundle.getState() != ResolverBundle.RESOLVED && !resolveBundle(bundle, cycle) && !developmentMode) {
						req.removePossibleSupplier(bundle);
						continue; // Bundle hasn't resolved
					}
				}
				// Check cyclic dependencies
				if (req.getBundle() != bundle) {
					if (bundle.getState() == ResolverBundle.RESOLVING)
						// If the bundle is RESOLVING, we have a cyclic dependency
						if (!cycle.contains(req.getBundle())) {
							cycle.add(req.getBundle());
							if (DEBUG_CYCLES)
								ResolverImpl.log("require-bundle cycle: " + req.getBundle() + " -> " + req.getSelectedSupplier()); //$NON-NLS-1$ //$NON-NLS-2$
						}
				}
				if (DEBUG_REQUIRES)
					ResolverImpl.log("Found match: " + bundle.getBundle() + ". Wiring"); //$NON-NLS-1$ //$NON-NLS-2$
				result = true;
			}
		}
		if (result || req.isOptional())
			return true; // If the req is optional then just return true

		return false;
	}

	// Resolve the supplied import. Returns true if the import can be resolved, false otherwise
	private boolean resolveImport(ResolverImport imp, ArrayList cycle) {
		if (DEBUG_IMPORTS)
			ResolverImpl.log("Trying to resolve: " + imp.getBundle() + ", " + imp.getName()); //$NON-NLS-1$ //$NON-NLS-2$
		if (imp.getSelectedSupplier() != null) {
			// Check for unrecorded cyclic dependency
			if (!cycle.contains(imp.getBundle())) {
				cycle.add(imp.getBundle());
				if (DEBUG_CYCLES)
					ResolverImpl.log("import-package cycle: " + imp.getBundle() + " -> " + imp.getSelectedSupplier() + " from " + imp.getSelectedSupplier().getBundle()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
			if (imp.isSatisfiedBy(export)) {
				int originalState = export.getExporter().getState();
				if (imp.isDynamic() && originalState != ResolverBundle.RESOLVED)
					continue; // Must not attempt to resolve an exporter when dynamic
				if (imp.getBundle() == export.getExporter() && !export.getExportPackageDescription().isRoot())
					continue; // Can't wire to our own re-export
				export.getExporter().addRef(imp.getBundle());
				// first add the possible supplier; this is done before resolving the supplier bundle to prevent endless cycle loops.
				imp.addPossibleSupplier(export);
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
						// remove the possible supplier
						imp.removePossibleSupplier(export);
						// add back the exports of this package from the importer
						for (int j = 0; j < importerExps.length; j++)
							resolverExports.put(importerExps[j].getName(), importerExps[j]);
						continue; // Bundle hasn't resolved || export has not been selected and is unavailable
					}
				} else if (export.isDropped())
					continue; // we already found a possible import that satisifies us; our export is dropped

				// Record any cyclic dependencies
				if (imp.getBundle() != export.getExporter())
					if (export.getExporter().getState() == ResolverBundle.RESOLVING) {
						// If the exporter is RESOLVING, we have a cyclic dependency
						if (!cycle.contains(imp.getBundle())) {
							cycle.add(imp.getBundle());
							if (DEBUG_CYCLES)
								ResolverImpl.log("import-package cycle: " + imp.getBundle() + " -> " + imp.getSelectedSupplier() + " from " + imp.getSelectedSupplier().getBundle()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						}
					}
				if (DEBUG_IMPORTS)
					ResolverImpl.log("Found match: " + export.getExporter() + ". Wiring " + imp.getBundle() + ":" + imp.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				result = true;
			}
		}

		if (result)
			return true;
		if (resolveImportReprovide(imp, cycle))
			return true;
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
			if (requires[i].getSelectedSupplier() == null)
				continue;
			ResolverExport[] exports = ((ResolverBundle) requires[i].getSelectedSupplier()).getExports(imp.getName());
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
					imp.addPossibleSupplier(re);
					return true;
				}
			}
			// Check requires of matching bundle (recurse down the chain)
			if (resolveImportReprovide0(imp, reexporter, (ResolverBundle) requires[i].getSelectedSupplier(), cycle, visited))
				return true;
		}
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
		for (int i = 0; i < resolvedBundles.length; i++) {
			if (!resolvedBundles[i].getBundle().isResolved())
				stateResolveBundle(resolvedBundles[i]);
		}
	}

	private void stateResolveConstraints(ResolverBundle rb) {
		ResolverImport[] imports = rb.getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			ResolverExport export = (ResolverExport) imports[i].getSelectedSupplier();
			BaseDescription supplier = export == null ? null : export.getExportPackageDescription();
			state.resolveConstraint(imports[i].getVersionConstraint(), supplier);
		}
		BundleConstraint[] requires = rb.getRequires();
		for (int i = 0; i < requires.length; i++) {
			ResolverBundle bundle = (ResolverBundle) requires[i].getSelectedSupplier();
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
		ResolverBundle host = (ResolverBundle) rb.getHost().getSelectedSupplier();
		ImportPackageSpecification[] imports = rb.getBundle().getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			ResolverImport hostImport = host == null ? null : host.getImport(imports[i].getName());
			ResolverExport export = (ResolverExport) (hostImport == null ? null : hostImport.getSelectedSupplier());
			BaseDescription supplier = export == null ? null : export.getExportPackageDescription();
			state.resolveConstraint(imports[i], supplier);
		}
		BundleSpecification[] requires = rb.getBundle().getRequiredBundles();
		for (int i = 0; i < requires.length; i++) {
			BundleConstraint hostRequire = host == null ? null : host.getRequire(requires[i].getName());
			ResolverBundle bundle = (ResolverBundle) (hostRequire == null ? null : hostRequire.getSelectedSupplier());
			BaseDescription supplier = bundle == null ? null : bundle.getBundle();
			state.resolveConstraint(requires[i], supplier);
		}
	}

	private void stateResolveBundle(ResolverBundle rb) {
		// if in dev mode then we want to tell the state about the constraints we were able to resolve
		if (!rb.isResolved() && !developmentMode)
			return;
		if (rb.isFragment())
			stateResolveFragConstraints(rb);
		else
			stateResolveConstraints(rb);
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
		for (int i = 0; i < imports.length; i++)
			if (imports[i].getSelectedSupplier() != null)
				exportsWiredTo.add(imports[i].getSelectedSupplier().getBaseDescription());
		ExportPackageDescription[] exportsWiredToArray = (ExportPackageDescription[]) exportsWiredTo.toArray(new ExportPackageDescription[exportsWiredTo.size()]);

		// Gather bundles that have been wired to
		BundleConstraint[] requires = rb.getRequires();
		ArrayList bundlesWiredTo = new ArrayList(requires.length);
		for (int i = 0; i < requires.length; i++)
			if (requires[i].getSelectedSupplier() != null)
				bundlesWiredTo.add(requires[i].getSelectedSupplier().getBaseDescription());
		BundleDescription[] bundlesWiredToArray = (BundleDescription[]) bundlesWiredTo.toArray(new BundleDescription[bundlesWiredTo.size()]);

		BundleDescription[] hostBundles = null;
		if (rb.isFragment()) {
			VersionSupplier[] matchingBundles = rb.getHost().getPossibleSuppliers();
			if (matchingBundles != null && matchingBundles.length > 0) {
				hostBundles = new BundleDescription[matchingBundles.length];
				for (int i = 0; i < matchingBundles.length; i++) {
					hostBundles[i] = matchingBundles[i].getBundle();
					if (rb.isNewFragmentExports() && hostBundles[i].isResolved()) {
						// update the host's set of selected exports
						ResolverExport[] hostExports = ((ResolverBundle) matchingBundles[i]).getSelectedExports();
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
				// populate the grouping checker with current imports
				groupingChecker.populateRoots(resolverImports[j].getBundle());
				if (resolveImport(resolverImports[j], new ArrayList())) {
					found = false;
					while (!found && resolverImports[j].getSelectedSupplier() != null) {
						if (groupingChecker.isDynamicConsistent(resolverImports[j].getBundle(), (ResolverExport) resolverImports[j].getSelectedSupplier()) != null)
							resolverImports[j].selectNextSupplier(); // not consistent; try the next
						else
							found = true; // found a valid wire
					}
					resolverImports[j].setName(null);
					if (!found) {
						// not found or there was a conflict; reset the suppliers and return null
						resolverImports[j].setPossibleSuppliers(null);
						return null;
					}
					// If the import resolved then return it's matching export
					if (DEBUG_IMPORTS)
						ResolverImpl.log("Resolved dynamic import: " + rb + ":" + resolverImports[j].getName() + " -> " + ((ResolverExport) resolverImports[j].getSelectedSupplier()).getExporter() + ":" + requestedPackage); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					ExportPackageDescription matchingExport = ((ResolverExport) resolverImports[j].getSelectedSupplier()).getExportPackageDescription();
					// If it is a wildcard import then clear the wire, so other
					// exported packages can be found for it
					if (importName.endsWith("*")) //$NON-NLS-1$
						resolverImports[j].setPossibleSuppliers(null);
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
			if (resolveImport(newImport, new ArrayList())) {
				while (newImport.getSelectedSupplier() != null) {
					if (groupingChecker.isDynamicConsistent(rb, (ResolverExport) newImport.getSelectedSupplier()) != null)
						newImport.selectNextSupplier();
					else
						break;
				}
				return ((ResolverExport) newImport.getSelectedSupplier()).getExportPackageDescription();
			}
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
			groupingChecker.remove(rb);
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
			groupingChecker.remove(re);
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
					if (requireBundles[i].getSelectedSupplier() == null) {
						ResolverImpl.log("        (r) " + rb.getBundle() + " -> NULL!!!"); //$NON-NLS-1$ //$NON-NLS-2$
					} else {
						ResolverImpl.log("        (r) " + rb.getBundle() + " -> " + requireBundles[i].getSelectedSupplier()); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			}
			// Hosts
			BundleConstraint hostSpec = rb.getHost();
			if (hostSpec != null) {
				VersionSupplier[] hosts = hostSpec.getPossibleSuppliers();
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
				if (imports[i].isDynamic() && imports[i].getSelectedSupplier() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> DYNAMIC"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else if (imports[i].isOptional() && imports[i].getSelectedSupplier() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> OPTIONAL (could not be wired)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else if (imports[i].getSelectedSupplier() == null) {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> NULL!!!"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				} else {
					ResolverImpl.log("        (w) " + imports[i].getBundle() + ":" + imports[i].getName() + " -> " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							((ResolverExport) imports[i].getSelectedSupplier()).getExporter() + ":" + imports[i].getSelectedSupplier().getName()); //$NON-NLS-1$
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

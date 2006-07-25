/*******************************************************************************
 * Copyright (c) 2004, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.resolver;

import java.util.*;

import org.eclipse.osgi.framework.internal.core.Constants;
import org.eclipse.osgi.service.resolver.*;

/**
 * An implementation for the StateHelper API. Access to this implementation is
 * provided  by the PlatformAdmin. Since this helper is a general facility for
 * state manipulation, it should not be tied to any implementation details.
 */
public class StateHelperImpl implements StateHelper {
	private static StateHelper instance = new StateHelperImpl();

	/**
	 * @see StateHelper
	 */
	public BundleDescription[] getDependentBundles(BundleDescription[] bundles) {
		if (bundles == null || bundles.length == 0)
			return new BundleDescription[0];

		Set reachable = new HashSet(bundles.length);
		for (int i = 0; i < bundles.length; i++) {
			if (!bundles[i].isResolved())
				continue;
			addDependentBundles(bundles[i], reachable);
		}
		return (BundleDescription[]) reachable.toArray(new BundleDescription[reachable.size()]);
	}

	private void addDependentBundles(BundleDescription bundle, Set reachable) {
		if (reachable.contains(bundle))
			return;
		reachable.add(bundle);
		BundleDescription[] dependents = bundle.getDependents();
		for (int i = 0; i < dependents.length; i++)
			addDependentBundles(dependents[i], reachable);
	}

	public BundleDescription[] getPrerequisites(BundleDescription[] bundles) {
		if (bundles == null || bundles.length == 0)
			return new BundleDescription[0];
		Set reachable = new HashSet(bundles.length);
		for (int i = 0; i < bundles.length; i++)
			addPrerequisites(bundles[i], reachable);
		return (BundleDescription[]) reachable.toArray(new BundleDescription[reachable.size()]);
	}

	private void addPrerequisites(BundleDescription bundle, Set reachable) {
		if (reachable.contains(bundle))
			return;
		reachable.add(bundle);
		List depList = ((BundleDescriptionImpl) bundle).getBundleDependencies();
		BundleDescription[] dependencies = (BundleDescription[]) depList.toArray(new BundleDescription[depList.size()]);
		for (int i = 0; i < dependencies.length; i++)
			addPrerequisites(dependencies[i], reachable);
	}

	private Map getExportedPackageMap(State state) {
		Map result = new HashMap(11);
		BundleDescription[] bundles = state.getBundles();
		for (int i = 0; i < bundles.length; i++) {
			ExportPackageDescription[] packages = bundles[i].getExportPackages();
			for (int j = 0; j < packages.length; j++) {
				ExportPackageDescription description = packages[j];
				Set exports = (Set) result.get(description.getName());
				if (exports == null) {
					exports = new HashSet(1);
					result.put(description.getName(), exports);
				}
				exports.add(description);
			}
		}
		return result;
	}

	private Map getGenericsMap(State state, boolean resolved) {
		Map result = new HashMap(11);
		BundleDescription[] bundles = state.getBundles();
		for (int i = 0; i < bundles.length; i++) {
			if (resolved && !bundles[i].isResolved())
				continue;  // discard unresolved bundles
			GenericDescription[] generics = bundles[i].getGenericCapabilities();
			for (int j = 0; j < generics.length; j++) {
				GenericDescription description = generics[j];
				Set genericSet = (Set) result.get(description.getName());
				if (genericSet == null) {
					genericSet = new HashSet(1);
					result.put(description.getName(), genericSet);
				}
				genericSet.add(description);
			}
		}
		return result;
	}

	private VersionConstraint[] getUnsatisfiedLeaves(State state, BundleDescription[] bundles) {
		Map packages = getExportedPackageMap(state);
		Map generics = getGenericsMap(state, false);
		HashSet result = new HashSet(11);
		for (int i = 0; i < bundles.length; i++) {
			BundleDescription description = bundles[i];
			VersionConstraint[] constraints = getUnsatisfiedConstraints(description);
			for (int j = 0; j < constraints.length; j++) {
				VersionConstraint constraint = constraints[j];
				boolean satisfied = false;
				if (constraint instanceof BundleSpecification || constraint instanceof HostSpecification) {
					BundleDescription[] suppliers = state.getBundles(constraint.getName());
					for (int k = 0; k < suppliers.length && !satisfied; k++) 
						satisfied |= constraint.isSatisfiedBy(suppliers[k]);
				} else if (constraint instanceof ImportPackageSpecification) {
					Set exports = (Set) packages.get(constraint.getName());
					if (exports != null) 
						for (Iterator iter = exports.iterator(); iter.hasNext() && !satisfied;)
							satisfied |= constraint.isSatisfiedBy((ExportPackageDescription) iter.next());
				} else if (constraint instanceof GenericSpecification) {
					Set genericSet = (Set) generics.get(constraint.getName());
					if (genericSet != null) 
						for (Iterator iter = genericSet.iterator(); iter.hasNext() && !satisfied;)
							satisfied |= constraint.isSatisfiedBy((GenericDescription) iter.next());
				}
				if (!satisfied)
					result.add(constraint);
			}
		}
		return (VersionConstraint[]) result.toArray(new VersionConstraint[result.size()]);

	}

	public VersionConstraint[] getUnsatisfiedLeaves(BundleDescription[] bundles) {
		if (bundles.length == 0)
			return new VersionConstraint[0];
		State state = bundles[0].getContainingState();
		return getUnsatisfiedLeaves(state, bundles);
	}

	/**
	 * @see StateHelper
	 */
	public VersionConstraint[] getUnsatisfiedConstraints(BundleDescription bundle) {
		State containingState = bundle.getContainingState();
		if (containingState == null)
			// it is a bug in the client to call this method when not attached to a state
			throw new IllegalStateException("Does not belong to a state"); //$NON-NLS-1$		
		List unsatisfied = new ArrayList();
		HostSpecification host = bundle.getHost();
		if (host != null)
			if (!host.isResolved() && !isResolvable(host))
				unsatisfied.add(host);
		BundleSpecification[] requiredBundles = bundle.getRequiredBundles();
		for (int i = 0; i < requiredBundles.length; i++)
			if (!requiredBundles[i].isResolved() && !isResolvable(requiredBundles[i]))
				unsatisfied.add(requiredBundles[i]);
		ImportPackageSpecification[] packages = bundle.getImportPackages();
		for (int i = 0; i < packages.length; i++)
			if (!packages[i].isResolved() && !isResolvable(packages[i]))
				unsatisfied.add(packages[i]);
		GenericSpecification[] generics = bundle.getGenericRequires();
		for (int i = 0; i < generics.length; i++)
			if (!generics[i].isResolved() && !isResolvable(generics[i]))
				unsatisfied.add(generics[i]);
		return (VersionConstraint[]) unsatisfied.toArray(new VersionConstraint[unsatisfied.size()]);
	}

	/**
	 * @see StateHelper
	 */
	public boolean isResolvable(ImportPackageSpecification constraint) {
		ExportPackageDescription[] exports = constraint.getBundle().getContainingState().getExportedPackages();
		for (int i = 0; i < exports.length; i++)
			if (constraint.isSatisfiedBy(exports[i]))
				return true;
		return false;
	}

	private boolean isResolvable(GenericSpecification constraint) {
		Map genericCapabilities = getGenericsMap(constraint.getBundle().getContainingState(), true);
		Set genericSet = (Set) genericCapabilities.get(constraint.getName());
		if (genericSet == null)
			return false;
		for (Iterator iter = genericSet.iterator(); iter.hasNext();)
			if (constraint.isSatisfiedBy((GenericDescription) iter.next()))
				return true;
		return false;
	}

	/**
	 * @see StateHelper
	 */
	public boolean isResolvable(BundleSpecification specification) {
		return isBundleConstraintResolvable(specification);
	}

	/**
	 * @see StateHelper
	 */
	public boolean isResolvable(HostSpecification specification) {
		return isBundleConstraintResolvable(specification);
	}

	/*
	 * Returns whether a bundle specification/host specification can be resolved.
	 */
	private boolean isBundleConstraintResolvable(VersionConstraint constraint) {
		BundleDescription[] availableBundles = constraint.getBundle().getContainingState().getBundles(constraint.getName());
		for (int i = 0; i < availableBundles.length; i++)
			if (availableBundles[i].isResolved() && constraint.isSatisfiedBy(availableBundles[i]))
				return true;
		return false;
	}

	public Object[][] sortBundles(BundleDescription[] toSort) {
		List references = new ArrayList(toSort.length);
		for (int i = 0; i < toSort.length; i++)
			if (toSort[i].isResolved())
				buildReferences(toSort[i], references);
		return ComputeNodeOrder.computeNodeOrder(toSort, (Object[][]) references.toArray(new Object[references.size()][]));
	}

	private void buildReferences(BundleDescription description, List references) {
		HostSpecification host = description.getHost();
		// it is a fragment
		if (host != null) {
			// just create a dependency between fragment and host
			if (host.getHosts() != null) {
				BundleDescription[] hosts = host.getHosts();
				for (int i = 0; i < hosts.length; i++)
					if (hosts[i] != description)
						references.add(new Object[] {description, hosts[i]});
			}
		} else {
			// it is a host
			buildReferences(description, ((BundleDescriptionImpl) description).getBundleDependencies(), references);
		}
	}

	private void buildReferences(BundleDescription description, List dependencies, List references) {
		for (Iterator iter = dependencies.iterator(); iter.hasNext();)
			addReference(description, (BundleDescription) iter.next(), references);
	}

	private void addReference(BundleDescription description, BundleDescription reference, List references) {
		// build the reference from the description
		if (description == reference || reference == null)
			return;

		references.add(new Object[] {description, reference});
	}

	public ExportPackageDescription[] getVisiblePackages(BundleDescription bundle) {
		StateImpl state = (StateImpl) bundle.getContainingState();
		boolean strict = false;
		if (state != null)
			strict = state.inStrictMode();
		ArrayList packageList = new ArrayList(); // list of all ExportPackageDescriptions that are visible
		ArrayList importList = new ArrayList(); // list of package names which are directly imported
		// get the list of directly imported packages first.
		ImportPackageSpecification[] imports = bundle.getImportPackages();
		for (int i = 0; i < imports.length; i++) {
			ExportPackageDescription pkgSupplier = (ExportPackageDescription) imports[i].getSupplier();
			if (pkgSupplier == null)
				continue;
			if (!isSystemExport(pkgSupplier))
				packageList.add(pkgSupplier);
			// get the sources of the required bundles of the exporter
			BundleSpecification[] requires = pkgSupplier.getExporter().getRequiredBundles();
			ArrayList visited = new ArrayList();
			for (int j = 0; j < requires.length; j++) {
				BundleDescription bundleSupplier = (BundleDescription) requires[j].getSupplier();
				if (bundleSupplier != null)
					getPackages(bundleSupplier, bundle.getSymbolicName(), importList, packageList, visited, strict, imports[i].getName());
			}
			importList.add(imports[i].getName()); // besure to add to direct import list
		}
		// now find all the packages that are visible from required bundles
		BundleSpecification[] requires = bundle.getRequiredBundles();
		ArrayList visited = new ArrayList(requires.length);
		for (int i = 0; i < requires.length; i++) {
			BundleDescription bundleSupplier = (BundleDescription) requires[i].getSupplier();
			if (bundleSupplier != null)
				getPackages(bundleSupplier, bundle.getSymbolicName(), importList, packageList, visited, strict, null);
		}
		return (ExportPackageDescription[]) packageList.toArray(new ExportPackageDescription[packageList.size()]);
	}

	private void getPackages(BundleDescription requiredBundle, String symbolicName, List importList, List packageList, List visited, boolean strict, String pkgName) {
		if (visited.contains(requiredBundle))
			return; // prevent duplicate entries and infinate loops incase of cycles
		visited.add(requiredBundle);
		// add all the exported packages from the required bundle; take x-friends into account.
		ExportPackageDescription[] exports = requiredBundle.getSelectedExports();
		ArrayList exportNames = new ArrayList(exports.length);
		for (int i = 0; i < exports.length; i++)
			if ((pkgName == null || exports[i].getName().equals(pkgName)) && !isSystemExport(exports[i]) && isFriend(symbolicName, exports[i], strict) && !importList.contains(exports[i].getName())) {
				packageList.add(exports[i]);
				exportNames.add(exports[i].getName());
			}
		// now look for exports from the required bundle.
		BundleSpecification[] requiredBundles = requiredBundle.getRequiredBundles();
		for (int i = 0; i < requiredBundles.length; i++)
			if (requiredBundles[i].getSupplier() != null)
				if ((pkgName != null && exportNames.size() > 0) || requiredBundles[i].isExported()) {
					// looking for a specific package and that package is exported by this bundle or adding all packages from a reexported bundle
					getPackages((BundleDescription) requiredBundles[i].getSupplier(), symbolicName, importList, packageList, visited, strict, pkgName);
				} else {
					// adding any exports from required bundles which we also export
					for (Iterator names = exportNames.iterator(); names.hasNext();)
						getPackages((BundleDescription) requiredBundles[i].getSupplier(), symbolicName, importList, packageList, visited, strict, (String) names.next());
				}
	}

	private boolean isSystemExport(ExportPackageDescription export) {
		return ((Integer) export.getDirective(ExportPackageDescriptionImpl.EQUINOX_EE)).intValue() >= 0;
	}

	private boolean isFriend(String consumerBSN, ExportPackageDescription export, boolean strict) {
		if (!strict)
			return true; // ignore friends rules if not in strict mode
		String[] friends = (String[]) export.getDirective(Constants.FRIENDS_DIRECTIVE);
		if (friends == null)
			return true; // no x-friends means it is wide open
		for (int i = 0; i < friends.length; i++)
			if (friends[i].equals(consumerBSN))
				return true; // the consumer is a friend
		return false;
	}

	public int getAccessCode(BundleDescription bundle, ExportPackageDescription export) {
		if (((Boolean) export.getDirective(Constants.INTERNAL_DIRECTIVE)).booleanValue())
			return ACCESS_DISCOURAGED;
		if (!isFriend(bundle.getSymbolicName(), export, true)) // pass strict here so that x-friends is processed
			return ACCESS_DISCOURAGED;
		return ACCESS_ENCOURAGED;
	}

	public static StateHelper getInstance() {
		return instance;
	}
}

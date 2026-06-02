/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.inject.Injector;

/**
 * EDT MCP Server plugin activator.
 * Uses OSGi ServiceTracker to obtain EDT platform services.
 */
public class Activator extends AbstractUIPlugin
{
    /** Plugin ID */
    public static final String PLUGIN_ID = "com.ditrix.edt.mcp.server"; //$NON-NLS-1$

    /** Singleton instance */
    private static Activator plugin;

    /** MCP Server instance */
    private McpServer mcpServer;

    /** Service trackers */
    private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;
    private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;
    private ServiceTracker<IConfigurationProvider, IConfigurationProvider> configurationProviderTracker;
    private ServiceTracker<IMarkerManager, IMarkerManager> markerManagerTracker;
    private ServiceTracker<ICheckScheduler, ICheckScheduler> checkSchedulerTracker;
    private ServiceTracker<ICheckRepository, ICheckRepository> checkRepositoryTracker;
    private ServiceTracker<IBmModelManager, IBmModelManager> bmModelManagerTracker;
    private ServiceTracker<IDerivedDataManagerProvider, IDerivedDataManagerProvider> derivedDataManagerProviderTracker;
    private ServiceTracker<IServicesOrchestrator, IServicesOrchestrator> servicesOrchestratorTracker;
    private ServiceTracker<BmAwareResourceSetProvider, BmAwareResourceSetProvider> resourceSetProviderTracker;
    private ServiceTracker<IApplicationManager, IApplicationManager> applicationManagerTracker;
    private ServiceTracker<INavigatorContentProviderStateProvider, INavigatorContentProviderStateProvider> navigatorStateProviderTracker;
    private ServiceTracker<IMdRefactoringService, IMdRefactoringService> mdRefactoringServiceTracker;
    private ServiceTracker<ITopObjectFqnGenerator, ITopObjectFqnGenerator> topObjectFqnGeneratorTracker;
    /**
     * EDT workspace CLI APIs are tracked by String class name and invoked via
     * reflection from the tools, keeping this bundle build-independent of
     * com._1c.g5.v8.dt.cli.api.
     */
    private ServiceTracker<Object, Object> exportConfigurationFilesApiTracker;
    private ServiceTracker<Object, Object> importConfigurationFilesApiTracker;

    /**
     * LanguageTool CLI APIs are tracked by String class name to keep this
     * bundle build-independent of the com.e1c.langtool.* bundles (LanguageTool
     * is installed separately via Help -&gt; Install New Software on both EDT
     * 2025.x and 2026.1; not bundled with the EDT base distribution). All
     * invocations on the returned services go through reflection — see
     * GenerateTranslationStringsTool, TranslateConfigurationTool, and
     * GetTranslationProjectInfoTool.
     */
    private ServiceTracker<Object, Object> generateTranslationStringsApiTracker;
    private ServiceTracker<Object, Object> synchronizeProjectApiTracker;
    private ServiceTracker<Object, Object> projectInformationApiTracker;

    /** Group service instance (created directly, not via OSGi DS to avoid circular references) */
    private IGroupService groupService;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        plugin = this;
        mcpServer = new McpServer();

        // In Tycho headless test runtime, avoid eager workspace/UI/platform initialization.
        // This prevents background platform startup races that can fail the test process.
        if (isHeadless())
        {
            logInfo("EDT MCP Server plugin started in headless mode (startup integrations skipped)"); //$NON-NLS-1$
            return;
        }

        // Register tools eagerly so descriptions are available in the preferences UI
        // even if the MCP server has not been started yet.
        mcpServer.registerTools();
        
        // Initialize service trackers
        v8ProjectManagerTracker = new ServiceTracker<>(context, IV8ProjectManager.class, null);
        v8ProjectManagerTracker.open();
        
        dtProjectManagerTracker = new ServiceTracker<>(context, IDtProjectManager.class, null);
        dtProjectManagerTracker.open();
        
        configurationProviderTracker = new ServiceTracker<>(context, IConfigurationProvider.class, null);
        configurationProviderTracker.open();
        
        markerManagerTracker = new ServiceTracker<>(context, IMarkerManager.class, null);
        markerManagerTracker.open();
        
        checkSchedulerTracker = new ServiceTracker<>(context, ICheckScheduler.class, null);
        checkSchedulerTracker.open();
        
        checkRepositoryTracker = new ServiceTracker<>(context, ICheckRepository.class, null);
        checkRepositoryTracker.open();
        
        bmModelManagerTracker = new ServiceTracker<>(context, IBmModelManager.class, null);
        bmModelManagerTracker.open();
        
        derivedDataManagerProviderTracker = new ServiceTracker<>(context, IDerivedDataManagerProvider.class, null);
        derivedDataManagerProviderTracker.open();
        
        servicesOrchestratorTracker = new ServiceTracker<>(context, IServicesOrchestrator.class, null);
        servicesOrchestratorTracker.open();
        
        resourceSetProviderTracker = new ServiceTracker<>(context, BmAwareResourceSetProvider.class, null);
        resourceSetProviderTracker.open();
        
        applicationManagerTracker = new ServiceTracker<>(context, IApplicationManager.class, null);
        applicationManagerTracker.open();
        
        navigatorStateProviderTracker = new ServiceTracker<>(context, INavigatorContentProviderStateProvider.class, null);
        navigatorStateProviderTracker.open();
        
        mdRefactoringServiceTracker = new ServiceTracker<>(context, IMdRefactoringService.class, null);
        mdRefactoringServiceTracker.open();

        topObjectFqnGeneratorTracker = new ServiceTracker<>(context, ITopObjectFqnGenerator.class, null);
        topObjectFqnGeneratorTracker.open();

        exportConfigurationFilesApiTracker = new ServiceTracker<>(
            context, "com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi", null); //$NON-NLS-1$
        exportConfigurationFilesApiTracker.open();

        importConfigurationFilesApiTracker = new ServiceTracker<>(
            context, "com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi", null); //$NON-NLS-1$
        importConfigurationFilesApiTracker.open();

        generateTranslationStringsApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi", null); //$NON-NLS-1$
        generateTranslationStringsApiTracker.open();

        synchronizeProjectApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi", null); //$NON-NLS-1$
        synchronizeProjectApiTracker.open();

        projectInformationApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi", null); //$NON-NLS-1$
        projectInformationApiTracker.open();

        // Create group service directly (not via OSGi DS to avoid circular references)
        groupService = new com.ditrix.edt.mcp.server.groups.internal.GroupServiceImpl();
        ((com.ditrix.edt.mcp.server.groups.internal.GroupServiceImpl) groupService).activate();
        
        // Initialize UI components only in non-headless mode
        if (!isHeadless())
        {
            // Initialize filter manager to reset toggle state on startup
            com.ditrix.edt.mcp.server.tags.ui.FilterByTagManager.getInstance();
            
            // Initialize navigator toolbar customizer to hide standard Collapse All button
            org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                try {
                    com.ditrix.edt.mcp.server.ui.NavigatorToolbarCustomizer.getInstance().initialize();
                } catch (Exception e) {
                    logError("Failed to initialize NavigatorToolbarCustomizer", e);
                }
            });
        }
        
        logInfo("EDT MCP Server plugin started"); //$NON-NLS-1$
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        if (mcpServer != null && mcpServer.isRunning())
        {
            mcpServer.stop();
        }
        
        // Close service trackers
        if (v8ProjectManagerTracker != null)
        {
            v8ProjectManagerTracker.close();
            v8ProjectManagerTracker = null;
        }
        if (dtProjectManagerTracker != null)
        {
            dtProjectManagerTracker.close();
            dtProjectManagerTracker = null;
        }
        if (configurationProviderTracker != null)
        {
            configurationProviderTracker.close();
            configurationProviderTracker = null;
        }
        if (markerManagerTracker != null)
        {
            markerManagerTracker.close();
            markerManagerTracker = null;
        }
        if (checkSchedulerTracker != null)
        {
            checkSchedulerTracker.close();
            checkSchedulerTracker = null;
        }
        if (checkRepositoryTracker != null)
        {
            checkRepositoryTracker.close();
            checkRepositoryTracker = null;
        }
        if (bmModelManagerTracker != null)
        {
            bmModelManagerTracker.close();
            bmModelManagerTracker = null;
        }
        if (derivedDataManagerProviderTracker != null)
        {
            derivedDataManagerProviderTracker.close();
            derivedDataManagerProviderTracker = null;
        }
        if (servicesOrchestratorTracker != null)
        {
            servicesOrchestratorTracker.close();
            servicesOrchestratorTracker = null;
        }
        if (resourceSetProviderTracker != null)
        {
            resourceSetProviderTracker.close();
            resourceSetProviderTracker = null;
        }
        if (applicationManagerTracker != null)
        {
            applicationManagerTracker.close();
            applicationManagerTracker = null;
        }
        if (navigatorStateProviderTracker != null)
        {
            navigatorStateProviderTracker.close();
            navigatorStateProviderTracker = null;
        }
        if (mdRefactoringServiceTracker != null)
        {
            mdRefactoringServiceTracker.close();
            mdRefactoringServiceTracker = null;
        }
        if (topObjectFqnGeneratorTracker != null)
        {
            topObjectFqnGeneratorTracker.close();
            topObjectFqnGeneratorTracker = null;
        }
        if (exportConfigurationFilesApiTracker != null)
        {
            exportConfigurationFilesApiTracker.close();
            exportConfigurationFilesApiTracker = null;
        }
        if (importConfigurationFilesApiTracker != null)
        {
            importConfigurationFilesApiTracker.close();
            importConfigurationFilesApiTracker = null;
        }
        if (generateTranslationStringsApiTracker != null)
        {
            generateTranslationStringsApiTracker.close();
            generateTranslationStringsApiTracker = null;
        }
        if (synchronizeProjectApiTracker != null)
        {
            synchronizeProjectApiTracker.close();
            synchronizeProjectApiTracker = null;
        }
        if (projectInformationApiTracker != null)
        {
            projectInformationApiTracker.close();
            projectInformationApiTracker = null;
        }

        // Dispose UI components only in non-headless mode
        if (!isHeadless())
        {
            // Dispose navigator toolbar customizer
            try
            {
                org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
                if (display != null && !display.isDisposed())
                {
                    display.syncExec(() -> {
                        try
                        {
                            com.ditrix.edt.mcp.server.ui.NavigatorToolbarCustomizer.getInstance().dispose();
                        }
                        catch (Exception e)
                        {
                            // Ignore - workbench may be closing
                        }
                    });
                }
            }
            catch (Exception e)
            {
                // Ignore - display may be disposed
            }
        }
        
        // Deactivate group service
        if (groupService instanceof com.ditrix.edt.mcp.server.groups.internal.GroupServiceImpl impl)
        {
            impl.deactivate();
        }
        groupService = null;

        // Stop update checker scheduler
        UpdateChecker.getInstance().stopScheduler();

        logInfo("EDT MCP Server plugin stopped"); //$NON-NLS-1$
        plugin = null;
        super.stop(context);
    }

    /**
     * Returns the singleton activator instance.
     * 
     * @return activator
     */
    public static Activator getDefault()
    {
        return plugin;
    }

    /**
     * Returns the IV8ProjectManager service.
     * 
     * @return project manager or null if not available
     */
    public IV8ProjectManager getV8ProjectManager()
    {
        if (v8ProjectManagerTracker == null)
        {
            return null;
        }
        return v8ProjectManagerTracker.getService();
    }

    /**
     * Returns the IDtProjectManager service.
     * 
     * @return DT project manager or null if not available
     */
    public IDtProjectManager getDtProjectManager()
    {
        if (dtProjectManagerTracker == null)
        {
            return null;
        }
        return dtProjectManagerTracker.getService();
    }

    /**
     * Returns the IConfigurationProvider service.
     * 
     * @return configuration provider or null if not available
     */
    public IConfigurationProvider getConfigurationProvider()
    {
        if (configurationProviderTracker == null)
        {
            return null;
        }
        return configurationProviderTracker.getService();
    }

    /**
     * Returns the MCP Server.
     * 
     * @return MCP server
     */
    public McpServer getMcpServer()
    {
        return mcpServer;
    }
    
    /**
     * Returns the IMarkerManager service for accessing EDT configuration problems.
     * 
     * @return marker manager or null if not available
     */
    public IMarkerManager getMarkerManager()
    {
        if (markerManagerTracker == null)
        {
            return null;
        }
        return markerManagerTracker.getService();
    }
    
    /**
     * Returns the ICheckScheduler service for scheduling EDT validations.
     * 
     * @return check scheduler or null if not available
     */
    public ICheckScheduler getCheckScheduler()
    {
        if (checkSchedulerTracker == null)
        {
            return null;
        }
        return checkSchedulerTracker.getService();
    }
    
    /**
     * Returns the ICheckRepository service for accessing check registry.
     * Used for converting short UIDs to symbolic check IDs.
     * 
     * @return check repository or null if not available
     */
    public ICheckRepository getCheckRepository()
    {
        if (checkRepositoryTracker == null)
        {
            return null;
        }
        return checkRepositoryTracker.getService();
    }
    
    /**
     * Returns the IBmModelManager service for BM model operations.
     * 
     * @return BM model manager or null if not available
     */
    public IBmModelManager getBmModelManager()
    {
        if (bmModelManagerTracker == null)
        {
            return null;
        }
        return bmModelManagerTracker.getService();
    }
    
    /**
     * Returns the IDerivedDataManagerProvider service for derived data operations.
     * Used for waiting for validation and other derived data computations.
     * 
     * @return derived data manager provider or null if not available
     */
    public IDerivedDataManagerProvider getDerivedDataManagerProvider()
    {
        if (derivedDataManagerProviderTracker == null)
        {
            return null;
        }
        return derivedDataManagerProviderTracker.getService();
    }
    
    /**
     * Returns the IServicesOrchestrator service for lifecycle management.
     * Used for waiting for project context lifecycle events.
     * 
     * @return services orchestrator or null if not available
     */
    public IServicesOrchestrator getServicesOrchestrator()
    {
        if (servicesOrchestratorTracker == null)
        {
            return null;
        }
        return servicesOrchestratorTracker.getService();
    }
    
    /**
     * Returns the BmAwareResourceSetProvider service for resolving EMF proxies.
     * Used for resolving platform type proxies in content assist.
     * 
     * @return resource set provider or null if not available
     */
    public BmAwareResourceSetProvider getResourceSetProvider()
    {
        if (resourceSetProviderTracker == null)
        {
            return null;
        }
        return resourceSetProviderTracker.getService();
    }
    
    /**
     * Returns the IApplicationManager service for managing applications.
     * Used for application lifecycle operations (update, start, etc.).
     * 
     * @return application manager or null if not available
     */
    public IApplicationManager getApplicationManager()
    {
        if (applicationManagerTracker == null)
        {
            return null;
        }
        return applicationManagerTracker.getService();
    }
    
    /**
     * Returns the INavigatorContentProviderStateProvider service.
     * Used for controlling navigator content filtering state.
     * 
     * @return navigator state provider or null if not available
     */
    public INavigatorContentProviderStateProvider getNavigatorStateProvider()
    {
        if (navigatorStateProviderTracker == null)
        {
            return null;
        }
        return navigatorStateProviderTracker.getService();
    }
    
    /**
     * Returns the IMdRefactoringService for metadata rename/delete refactoring.
     * 
     * @return refactoring service or null if not available
     */
    public IMdRefactoringService getMdRefactoringService()
    {
        if (mdRefactoringServiceTracker == null)
        {
            return null;
        }
        return mdRefactoringServiceTracker.getService();
    }

    /**
     * Returns the {@link ITopObjectFqnGenerator} service used to compute the
     * canonical BM top-object FQN for external-property objects (e.g. the
     * content {@code Form} referenced by a {@code BasicForm}).
     * <p>
     * This is the same generator EDT's own form infrastructure uses (see
     * {@code com._1c.g5.v8.dt.form.service.common.impl.ExtInfoManagementService}).
     * Using it guarantees the content form is attached under the FQN the BM
     * namespace/store layer expects, so the object resolves on subsequent
     * lookups instead of failing with "No store with '&lt;id&gt;' is assigned
     * to namespace".
     *
     * @return the top-object FQN generator, or {@code null} if not available
     */
    public ITopObjectFqnGenerator getTopObjectFqnGenerator()
    {
        if (topObjectFqnGeneratorTracker == null)
        {
            return null;
        }
        return topObjectFqnGeneratorTracker.getService();
    }

    /**
     * Returns the IModelObjectFactory used to create metadata (mdclass) objects
     * with EDT default content (the same factory the "New" wizards use).
     * <p>
     * IMPORTANT: {@link IModelObjectFactory} is contributed by several language
     * plugins (one factory per language/EPackage). A plain OSGi service lookup
     * (ServiceTracker / ServiceAccess) returns an arbitrary implementation —
     * in practice the GeographicalSchemaObjectFactory — which cannot create
     * mdclass objects (Catalog, Document, CommonModule, ...) and throws an
     * uncaught "not a valid classifier" exception. We therefore resolve the
     * factory strictly from the MD language Guice injector, which binds
     * IModelObjectFactory to com._1c.g5.v8.dt.md.model.MdObjectFactory.
     *
     * @return MD model object factory or null if not available
     */
    public IModelObjectFactory getModelObjectFactory()
    {
        try
        {
            MdPlugin mdPlugin = MdPlugin.getDefault();
            if (mdPlugin != null)
            {
                Injector injector = mdPlugin.getInjector();
                if (injector != null)
                {
                    return injector.getInstance(IModelObjectFactory.class);
                }
            }
        }
        catch (Exception e)
        {
            logError("Failed to obtain MD IModelObjectFactory from MdPlugin injector", e); //$NON-NLS-1$
        }
        return null;
    }

    /** Symbolic name of the EDT form bundle that owns the form Guice injector. */
    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$

    /** Internal (non-exported) class that exposes the form bundle Guice injector. */
    private static final String FORM_PLUGIN_CLASS = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$

    /**
     * Public, exported service class that lives in the form bundle. Loading it
     * <em>through the form bundle</em> triggers the bundle's lazy activation
     * ({@code Bundle-ActivationPolicy: lazy}), so {@code FormPlugin.getDefault()}
     * stops returning {@code null}.
     */
    private static final String FORM_SERVICE_CLASS =
        "com._1c.g5.v8.dt.form.service.FormItemInformationService"; //$NON-NLS-1$

    /**
     * Human-readable reason the last {@link #getFormItemInformationService()}
     * call returned {@code null}. Surfaced verbatim by the form event tool so a
     * repeated failure shows the exact failing step rather than a generic
     * "service not available". {@code null} after a successful lookup.
     */
    private volatile String lastFormServiceDiagnostic;

    /**
     * Returns the diagnostic describing why the most recent
     * {@link #getFormItemInformationService()} call yielded {@code null}.
     *
     * @return the last failure reason, or {@code null} if the last lookup
     *         succeeded or was never attempted
     */
    public String getFormItemInformationServiceDiagnostic()
    {
        return lastFormServiceDiagnostic;
    }

    /**
     * Returns the {@link FormItemInformationService} the form editor uses to
     * compute, among other things, the set of events a form item supports
     * ({@code getAllowedEvents(FormVisualEntity)}).
     * <p>
     * The service is <strong>not</strong> registered as an OSGi service (the
     * form bundle's {@code FormPlugin} registers {@code IFormItemTypeInformationService},
     * {@code IFormItemManagementService}, … but never {@code FormItemInformationService}),
     * so a {@code ServiceTracker} cannot reach it. It is a Guice {@code @Singleton}
     * with an {@code @Inject IRuntimeVersionSupport} field, so a plain
     * {@code new FormItemInformationService()} yields a half-built instance whose
     * event lookup NPEs. The fully wired instance must be pulled from the form
     * bundle's Guice injector via just-in-time binding — the same pattern
     * {@link #getModelObjectFactory()} uses for the MD factory.
     * <p>
     * The injector lives on the non-exported internal class
     * {@code com._1c.g5.v8.dt.internal.form.FormPlugin}, reached reflectively.
     * {@code FormPlugin} is a lazy-activation {@link org.eclipse.core.runtime.Plugin}:
     * its {@code getDefault()} returns {@code null} until the bundle is started,
     * and the previous implementation failed precisely here when nothing had yet
     * touched a form in the session. We therefore force activation first — by
     * loading the public service class through the form bundle (which trips lazy
     * activation) and, as a fallback, {@code Bundle.start(START_TRANSIENT)} — then
     * read {@code getDefault().getInjector()} and resolve the singleton. Every
     * step records {@link #lastFormServiceDiagnostic} so a {@code null} return is
     * always explained.
     *
     * @return the wired form item information service, or {@code null} if the
     *         form bundle or its injector is not available (see
     *         {@link #getFormItemInformationServiceDiagnostic()})
     */
    public FormItemInformationService getFormItemInformationService()
    {
        lastFormServiceDiagnostic = null;
        Bundle formBundle = Platform.getBundle(FORM_BUNDLE_ID);
        if (formBundle == null)
        {
            lastFormServiceDiagnostic =
                "form bundle '" + FORM_BUNDLE_ID + "' not found in the running platform"; //$NON-NLS-1$ //$NON-NLS-2$
            logError(lastFormServiceDiagnostic, null);
            return null;
        }

        // Force the lazily-activated form bundle to start so FormPlugin.getDefault()
        // is populated. Loading an exported class through the bundle is the standard
        // way to trip lazy activation; start(START_TRANSIENT) is the explicit fallback.
        String activationProblem = ensureFormBundleActive(formBundle);

        try
        {
            Class<?> formPluginClass = formBundle.loadClass(FORM_PLUGIN_CLASS);
            // FormPlugin is the non-exported internal class
            // com._1c.g5.v8.dt.internal.form.FormPlugin. getDefault()/getInjector()
            // are public methods, but because their DECLARING class is not public to
            // this bundle, Method.invoke enforces access on the declaring class and
            // throws IllegalAccessException ("cannot access a member of class ... with
            // modifiers public") unless the Method is made accessible first. We must
            // call setAccessible(true) BEFORE invoke on every Method declared on this
            // internal class.
            Method getDefaultMethod = formPluginClass.getMethod("getDefault"); //$NON-NLS-1$
            getDefaultMethod.setAccessible(true);
            Object formPlugin = getDefaultMethod.invoke(null);
            if (formPlugin == null)
            {
                lastFormServiceDiagnostic = "FormPlugin.getDefault() is null (form bundle state=" //$NON-NLS-1$
                    + bundleStateName(formBundle.getState()) + ")" //$NON-NLS-1$
                    + (activationProblem != null ? "; activation: " + activationProblem : ""); //$NON-NLS-1$ //$NON-NLS-2$
                logError(lastFormServiceDiagnostic, null);
                return null;
            }

            // Same access rule as getDefault() above: getInjector() is declared on the
            // internal FormPlugin class, so the Method must be made accessible before
            // invoke to avoid IllegalAccessException on com.google.inject.internal.InjectorImpl.
            Method getInjectorMethod = formPluginClass.getMethod("getInjector"); //$NON-NLS-1$
            getInjectorMethod.setAccessible(true);
            Object injectorObj = getInjectorMethod.invoke(formPlugin);
            if (injectorObj == null)
            {
                lastFormServiceDiagnostic = "FormPlugin.getInjector() returned null"; //$NON-NLS-1$
                logError(lastFormServiceDiagnostic, null);
                return null;
            }
            if (!(injectorObj instanceof Injector))
            {
                lastFormServiceDiagnostic = "FormPlugin.getInjector() returned a " //$NON-NLS-1$
                    + injectorObj.getClass().getName() + " not assignable to com.google.inject.Injector " //$NON-NLS-1$
                    + "(class-space mismatch between this bundle and the form bundle)"; //$NON-NLS-1$
                logError(lastFormServiceDiagnostic, null);
                return null;
            }

            // Typed call (the bundle imports com.google.inject) — JIT-binds the
            // @Singleton, wiring its @Inject IRuntimeVersionSupport from OSGi.
            Injector injector = (Injector)injectorObj;
            FormItemInformationService service = injector.getInstance(FormItemInformationService.class);
            if (service == null)
            {
                lastFormServiceDiagnostic = "injector.getInstance(FormItemInformationService) returned null"; //$NON-NLS-1$
                logError(lastFormServiceDiagnostic, null);
                return null;
            }
            return service;
        }
        catch (Throwable e)
        {
            // ConfigurationException / ProvisionException from Guice when the
            // @Inject IRuntimeVersionSupport .toService() binding cannot be
            // satisfied land here; report the concrete cause.
            Throwable root = rootCause(e);
            lastFormServiceDiagnostic = "obtaining FormItemInformationService threw " //$NON-NLS-1$
                + root.getClass().getName()
                + (root.getMessage() != null ? ": " + root.getMessage() : "") //$NON-NLS-1$ //$NON-NLS-2$
                + (activationProblem != null ? " (activation: " + activationProblem + ")" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            logError("Failed to obtain FormItemInformationService from form bundle injector: " //$NON-NLS-1$
                + lastFormServiceDiagnostic, e);
            return null;
        }
    }

    /**
     * Ensures the lazily-activated form bundle is started. First loads an
     * exported class through the bundle (the standard lazy-activation trigger),
     * then, if the bundle is still not {@code ACTIVE}, calls
     * {@code start(START_TRANSIENT)} explicitly.
     *
     * @param formBundle the form bundle
     * @return {@code null} on success, otherwise a short description of what
     *         prevented activation (used only for diagnostics)
     */
    private static String ensureFormBundleActive(Bundle formBundle)
    {
        try
        {
            // Touch an exported class to trip Bundle-ActivationPolicy: lazy.
            formBundle.loadClass(FORM_SERVICE_CLASS);
        }
        catch (Throwable e)
        {
            return "loadClass(" + FORM_SERVICE_CLASS + ") failed: " + e; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (formBundle.getState() == Bundle.ACTIVE)
        {
            return null;
        }
        try
        {
            formBundle.start(Bundle.START_TRANSIENT);
            return null;
        }
        catch (Throwable e)
        {
            return "start(START_TRANSIENT) failed (state=" //$NON-NLS-1$
                + bundleStateName(formBundle.getState()) + "): " + e; //$NON-NLS-1$
        }
    }

    /**
     * @param state an OSGi {@link Bundle} state constant
     * @return a readable name for the state
     */
    private static String bundleStateName(int state)
    {
        switch (state)
        {
        case Bundle.UNINSTALLED:
            return "UNINSTALLED"; //$NON-NLS-1$
        case Bundle.INSTALLED:
            return "INSTALLED"; //$NON-NLS-1$
        case Bundle.RESOLVED:
            return "RESOLVED"; //$NON-NLS-1$
        case Bundle.STARTING:
            return "STARTING"; //$NON-NLS-1$
        case Bundle.STOPPING:
            return "STOPPING"; //$NON-NLS-1$
        case Bundle.ACTIVE:
            return "ACTIVE"; //$NON-NLS-1$
        default:
            return "0x" + Integer.toHexString(state); //$NON-NLS-1$
        }
    }

    /**
     * @param t a throwable
     * @return the deepest non-null cause (or {@code t} itself)
     */
    private static Throwable rootCause(Throwable t)
    {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current)
        {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi
     * (EDT "Export → Configuration to XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     */
    public Object getExportConfigurationFilesApi()
    {
        if (exportConfigurationFilesApiTracker == null)
        {
            return null;
        }
        return exportConfigurationFilesApiTracker.getService();
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi
     * (EDT "Import → Configuration from XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     */
    public Object getImportConfigurationFilesApi()
    {
        if (importConfigurationFilesApiTracker == null)
        {
            return null;
        }
        return importConfigurationFilesApiTracker.getService();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi
     * used to invoke the LanguageTool translation-strings generator. The
     * action is invoked on the configuration project (V8ConfigurationNature)
     * and writes placeholder keys into the .lstr/.trans/.dict storages
     * declared on the project (each storage routes to either an external
     * dictionary storage project — a plain Eclipse project with the
     * dependentProjectNature — or to the configuration itself).
     *
     * <p>Typed as {@code Object} — callers invoke via reflection so this bundle
     * has no build-time dependency on com.e1c.langtool.*, which is not shipped
     * with EDT 2026.1. Returns null when LanguageTool is not installed.
     *
     * @return generator API (as Object) or null if not available
     */
    public Object getGenerateTranslationStringsApi()
    {
        if (generateTranslationStringsApiTracker == null)
        {
            return null;
        }
        return generateTranslationStringsApiTracker.getService();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi used to
     * invoke the LanguageTool "Translate configuration" action (propagates
     * dictionary changes from the source project to all its dependent
     * translation projects, producing the translated artifacts).
     *
     * <p>Typed as {@code Object} — callers invoke via reflection so this bundle
     * has no build-time dependency on com.e1c.langtool.*, which is not shipped
     * with EDT 2026.1. Returns null when LanguageTool is not installed.
     *
     * @return synchronize project API (as Object) or null if not available
     */
    public Object getSynchronizeProjectApi()
    {
        if (synchronizeProjectApiTracker == null)
        {
            return null;
        }
        return synchronizeProjectApiTracker.getService();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi —
     * typed as {@code Object}, callers invoke via reflection. Returns null
     * when LanguageTool is not installed.
     */
    public Object getProjectInformationApi()
    {
        if (projectInformationApiTracker == null)
        {
            return null;
        }
        return projectInformationApiTracker.getService();
    }

    /**
     * Returns the IGroupService for group operations.
     * Used for virtual folder groups in the Navigator.
     * 
     * @return group service or null if not available
     */
    public IGroupService getGroupService()
    {
        return groupService;
    }
    
    /**
     * Static convenience method to get the group service.
     * 
     * @return group service or null if not available
     */
    public static IGroupService getGroupServiceStatic()
    {
        Activator activator = getDefault();
        return activator != null ? activator.getGroupService() : null;
    }

    /**
     * Logs an info message.
     * 
     * @param message the message
     */
    public static void logInfo(String message)
    {
        if (plugin != null)
        {
            plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }
    
    /**
     * Logs a debug message.
     * Only logs if debug mode is enabled (via .options file or preference).
     * 
     * @param message the debug message
     */
    public static void logDebug(String message)
    {
        // Disabled by default - enable by uncommenting the body below for troubleshooting
        // if (plugin != null)
        // {
        //     plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, "[DEBUG] " + message));
        // }
    }

    /**
     * Logs a warning message.
     * 
     * @param message the warning message
     */
    public static void logWarning(String message)
    {
        if (plugin != null)
        {
            plugin.getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }
    }

    /**
     * Logs an error.
     * 
     * @param message the message
     * @param e the exception
     */
    public static void logError(String message, Throwable e)
    {
        if (plugin != null)
        {
            plugin.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
        }
    }

    /**
     * Checks if the application is running in headless mode (no UI).
     * 
     * @return true if headless, false otherwise
     */
    private static boolean isHeadless()
    {
        // Check headless indicators without accessing Display.
        // (Display.getDefault() initializes GTK and fails in headless environments.)

        // 1) Eclipse test mode property
        String testSuite = System.getProperty("org.eclipse.ui.testsuite"); //$NON-NLS-1$
        if ("true".equals(testSuite)) //$NON-NLS-1$
        {
            return true;
        }

        // 2) Eclipse application type (Tycho uses headlesstest)
        String eclipseApplication = System.getProperty("eclipse.application"); //$NON-NLS-1$
        if (eclipseApplication != null && eclipseApplication.contains("headless")) //$NON-NLS-1$
        {
            return true;
        }

        // 3) Standard AWT headless flag (if provided by runtime)
        String awtHeadless = System.getProperty("java.awt.headless"); //$NON-NLS-1$
        if ("true".equalsIgnoreCase(awtHeadless)) //$NON-NLS-1$
        {
            return true;
        }

        // Default to false (assume UI is available)
        return false;
    }
}

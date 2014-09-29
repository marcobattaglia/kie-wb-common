package org.kie.workbench.common.services.datamodel.backend.server.cache;

import java.io.IOException;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;

import java.util.LinkedList;

import org.drools.core.rule.TypeMetaInfo;
import org.drools.workbench.models.datamodel.imports.Import;
import org.drools.workbench.models.datamodel.imports.Imports;
import org.drools.workbench.models.datamodel.oracle.ProjectDataModelOracle;
import org.drools.workbench.models.datamodel.oracle.TypeSource;
import org.guvnor.common.services.backend.cache.LRUCache;
import org.guvnor.common.services.project.builder.events.InvalidateDMOProjectCacheEvent;
import org.guvnor.common.services.project.model.ProjectImports;
import org.guvnor.common.services.project.service.POMService;
import org.kie.scanner.KieModuleMetaData;
import org.kie.workbench.common.services.backend.builder.Builder;
import org.kie.workbench.common.services.backend.builder.LRUBuilderCache;
import org.kie.workbench.common.services.datamodel.backend.server.builder.projects.ProjectDataModelOracleBuilder;
import org.kie.workbench.common.services.shared.project.KieProject;
import org.kie.workbench.common.services.shared.project.KieProjectService;
import org.kie.workbench.common.services.shared.project.ProjectImportsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.commons.validation.PortablePreconditions;
import org.uberfire.io.IOService;
import org.uberfire.java.nio.file.Files;

/**
 * A simple LRU cache for Project DataModelOracles
 */
@ApplicationScoped
@Named("ProjectDataModelOracleCache")
public class LRUProjectDataModelOracleCache extends LRUCache<KieProject, ProjectDataModelOracle> {

    private static final Logger log = LoggerFactory.getLogger( LRUProjectDataModelOracleCache.class );

    @Inject
    private POMService pomService;

    @Inject
    @Named("ioStrategy")
    private IOService ioService;

    @Inject
    private KieProjectService projectService;

    @Inject
    private ProjectImportsService importsService;

    @Inject
    private LRUBuilderCache cache;

    public synchronized void invalidateProjectCache( @Observes final InvalidateDMOProjectCacheEvent event ) {
        PortablePreconditions.checkNotNull( "event",
                                            event );
        final Path resourcePath = event.getResourcePath();
        final KieProject project = projectService.resolveProject( resourcePath );

        //If resource was not within a Project there's nothing to invalidate
        if ( project != null ) {
            invalidateCache( project );
        }
    }

    //Check the ProjectOracle for the Project has been created, otherwise create one!
    public synchronized ProjectDataModelOracle assertProjectDataModelOracle( final KieProject project ) {
        ProjectDataModelOracle projectOracle = getEntry( project );
        if ( projectOracle == null ) {
            projectOracle = makeProjectOracle( project );
            setEntry( project,
                      projectOracle );
        }
        return projectOracle;
    }

    private ProjectDataModelOracle makeProjectOracle( final KieProject project ) {
        //Get a Builder for the project
        final Builder builder = cache.assertBuilder( project );

        //Create the ProjectOracle...
        final KieModuleMetaData kieModuleMetaData = KieModuleMetaData.Factory.newKieModuleMetaData( builder.getKieModuleIgnoringErrors() );
        final ProjectDataModelOracleBuilder pdBuilder = ProjectDataModelOracleBuilder.newProjectOracleBuilder();

        // Add all packageLinkedList
        //first test change by mbattagl@redhat.com 
        //what are the package with the business model? 
        //The answer could be in a file inside META-INF folder, or in a pom.xml properties
        //Now it's hard coded in the if condition 
        log.info("Package to register are limited!");
        LinkedList<String> packagesToRegister = new LinkedList<String>();
        for(String packageName : kieModuleMetaData.getPackages()){
        	if(packageName.startsWith("org.mortgages") || packageName.startsWith("org.decisyon")){
        		packagesToRegister.add(packageName);
        	}
        }
       // pdBuilder.addPackages( kieModuleMetaData.getPackages() );
        pdBuilder.addPackages(packagesToRegister);
        

        //Add all classes from the KieModule metaData
//        for ( final String packageName : kieModuleMetaData.getPackages() ) {
        for ( final String packageName : packagesToRegister) {
            for ( final String className : kieModuleMetaData.getClasses( packageName ) ) {
                try {
                    final Class clazz = kieModuleMetaData.getClass( packageName,
                                                                    className );
                    final TypeMetaInfo typeMetaInfo = kieModuleMetaData.getTypeMetaInfo( clazz );
                    final TypeSource typeSource = builder.getClassSource( kieModuleMetaData,
                                                                          clazz );
                    pdBuilder.addClass( clazz,
                                        typeMetaInfo.isEvent(),
                                        typeSource );

                } catch ( Throwable e ) {
                    log.error( e.getMessage() );
                }
            }
        }

        //Add external imports. The availability of these classes is checked in Builder and failed fast. Here we load them into the DMO
        final org.uberfire.java.nio.file.Path nioExternalImportsPath = Paths.convert( project.getImportsPath() );
        if ( Files.exists( nioExternalImportsPath ) ) {
            final Path externalImportsPath = Paths.convert( nioExternalImportsPath );
            final ProjectImports projectImports = importsService.load( externalImportsPath );
            final Imports imports = projectImports.getImports();
            for ( final Import item : imports.getImports() ) {
                try {
                    Class clazz = this.getClass().getClassLoader().loadClass( item.getType() );
                    pdBuilder.addClass( clazz,
                                        false,
                                        TypeSource.JAVA_DEPENDENCY );
                } catch ( ClassNotFoundException cnfe ) {
                    //This would have been raised to the user by Builder's validation but record the error here too
                    log.error( cnfe.getMessage() );
                } catch ( IOException ioe ) {
                    log.error( ioe.getMessage() );
                }
            }
        }

        return pdBuilder.build();
    }
}


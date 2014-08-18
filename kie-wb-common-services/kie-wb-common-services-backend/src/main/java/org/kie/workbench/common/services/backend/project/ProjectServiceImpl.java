/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.workbench.common.services.backend.project;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.drools.workbench.models.datamodel.imports.Import;
import org.guvnor.common.services.backend.exceptions.ExceptionUtilities;
import org.guvnor.common.services.project.backend.server.AbstractProjectService;
import org.guvnor.common.services.project.backend.server.ProjectConfigurationContentHandler;
import org.guvnor.common.services.project.backend.server.utils.IdentifierUtils;
import org.guvnor.common.services.project.builder.events.InvalidateDMOProjectCacheEvent;
import org.guvnor.common.services.project.events.DeleteProjectEvent;
import org.guvnor.common.services.project.events.NewPackageEvent;
import org.guvnor.common.services.project.events.NewProjectEvent;
import org.guvnor.common.services.project.events.RenameProjectEvent;
import org.guvnor.common.services.project.model.POM;
import org.guvnor.common.services.project.model.Package;
import org.guvnor.common.services.project.model.Project;
import org.guvnor.common.services.project.model.ProjectImports;
import org.guvnor.common.services.project.service.POMService;
import org.guvnor.structure.repositories.Repository;
import org.guvnor.structure.server.config.ConfigurationFactory;
import org.guvnor.structure.server.config.ConfigurationService;
import org.jboss.errai.bus.server.annotations.Service;
import org.jboss.errai.security.shared.api.identity.User;
import org.kie.workbench.common.services.shared.kmodule.KModuleService;
import org.kie.workbench.common.services.shared.project.KieProject;
import org.kie.workbench.common.services.shared.project.KieProjectService;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.io.IOService;
import org.uberfire.java.nio.file.FileAlreadyExistsException;
import org.uberfire.java.nio.file.FileSystem;
import org.uberfire.java.nio.file.Files;
import org.uberfire.rpc.SessionInfo;

@Service
@ApplicationScoped
public class ProjectServiceImpl
        extends AbstractProjectService<KieProject>
        implements KieProjectService {

    private static final String PROJECT_IMPORTS_PATH = "project.imports";
    private static final String KMODULE_PATH = "src/main/resources/META-INF/kmodule.xml";

    @Inject
    private KModuleService kModuleService;

    public ProjectServiceImpl() {
    }

    @Inject
    public ProjectServiceImpl( @Named("ioStrategy") IOService ioService,
                               POMService pomService,
                               ProjectConfigurationContentHandler projectConfigurationContentHandler,
                               ConfigurationService configurationService,
                               ConfigurationFactory configurationFactory,
                               Event<NewProjectEvent> newProjectEvent,
                               Event<NewPackageEvent> newPackageEvent,
                               Event<RenameProjectEvent> renameProjectEvent,
                               Event<DeleteProjectEvent> deleteProjectEvent,
                               Event<InvalidateDMOProjectCacheEvent> invalidateDMOCache,
                               User identity,
                               SessionInfo sessionInfo ) {
        super( ioService, pomService, projectConfigurationContentHandler, configurationService,
               configurationFactory, newProjectEvent, newPackageEvent, renameProjectEvent, deleteProjectEvent,
               invalidateDMOCache, identity, sessionInfo );
    }

    @Override
    public KieProject newProject(
            final Repository repository,
            final String projectName,
            final POM pom,
            final String baseUrl ) {
        final FileSystem fs = Paths.convert( repository.getRoot() ).getFileSystem();
        try {
            //Projects are always created in the FS root
            final Path fsRoot = repository.getRoot();
            final Path projectRootPath = Paths.convert( Paths.convert( fsRoot ).resolve( projectName ) );

            ioService.startBatch( fs, makeCommentedOption( "New project [" + projectName + "]" ) );

            //Set-up project structure and KModule.xml
            kModuleService.setUpKModuleStructure( projectRootPath );

            //Create POM.xml
            pomService.create( projectRootPath,
                               baseUrl,
                               pom );

            //Create Project configuration
            final Path projectConfigPath = Paths.convert( Paths.convert( projectRootPath ).resolve( PROJECT_IMPORTS_PATH ) );

            if ( ioService.exists( Paths.convert( projectConfigPath ) ) ) {
                throw new FileAlreadyExistsException( projectConfigPath.toString() );
            }
            ioService.write( Paths.convert( projectConfigPath ),
                             projectConfigurationContentHandler.toString( createProjectImports() ) );

            //Raise an event for the new project
            final KieProject project = resolveProject( projectRootPath );
            newProjectEvent.fire( new NewProjectEvent( project, sessionInfo ) );

            //Create a default workspace based on the GAV
            final String legalJavaGroupId[] = IdentifierUtils.convertMavenIdentifierToJavaIdentifier( pom.getGav().getGroupId().split( "\\.",
                                                                                                                                       -1 ) );
            final String legalJavaArtifactId[] = IdentifierUtils.convertMavenIdentifierToJavaIdentifier( pom.getGav().getArtifactId().split( "\\.",
                                                                                                                                             -1 ) );
            final String defaultWorkspacePath = StringUtils.join( legalJavaGroupId,
                                                                  "/" ) + "/" + StringUtils.join( legalJavaArtifactId,
                                                                                                  "/" );
            final Path defaultPackagePath = Paths.convert( Paths.convert( projectRootPath ).resolve( MAIN_RESOURCES_PATH ) );
            final org.guvnor.common.services.project.model.Package defaultPackage = resolvePackage( defaultPackagePath );
            final Package defaultWorkspacePackage = doNewPackage( defaultPackage,
                                                                  defaultWorkspacePath,
                                                                  false );

            //Raise an event for the new project's default workspace
            newPackageEvent.fire( new NewPackageEvent( defaultWorkspacePackage ) );

            //Return new project
            return project;

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        } finally {
            ioService.endBatch();
        }
    }

    @Override
    public KieProject simpleProjectInstance( final org.uberfire.java.nio.file.Path nioProjectRootPath ) {
        final Path projectRootPath = Paths.convert( nioProjectRootPath );

        return new KieProject( projectRootPath,
                               Paths.convert( nioProjectRootPath.resolve( POM_PATH ) ),
                               Paths.convert( nioProjectRootPath.resolve( KMODULE_PATH ) ),
                               Paths.convert( nioProjectRootPath.resolve( PROJECT_IMPORTS_PATH ) ),
                               projectRootPath.getFileName() );
    }

    @Override
    public KieProject resolveProject( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return null;
            }

            //Check if resource is the project root
            org.uberfire.java.nio.file.Path path = Paths.convert( resource ).normalize();

            //A project root is the folder containing the pom.xml file. This will be the parent of the "src" folder
            if ( Files.isRegularFile( path ) ) {
                path = path.getParent();
            }
            if ( hasPom( path ) && hasKModule( path ) ) {
                return makeProject( path );
            }
            while ( path.getNameCount() > 0 && !path.getFileName().toString().equals( SOURCE_FILENAME ) ) {
                path = path.getParent();
            }
            if ( path.getNameCount() == 0 ) {
                return null;
            }
            path = path.getParent();
            if ( path.getNameCount() == 0 || path == null ) {
                return null;
            }
            if ( !hasPom( path ) ) {
                return null;
            }
            if ( !hasKModule( path ) ) {
                return null;
            }
            return makeProject( path );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    @Override
    protected KieProject makeProject( final org.uberfire.java.nio.file.Path nioProjectRootPath ) {
        final KieProject project = simpleProjectInstance( nioProjectRootPath );

        addSecurityRoles( project );

        return project;
    }

    @Override
    public Package resolvePackage( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return null;
            }

            //If Path is not within a Project we cannot resolve a package
            final Project project = resolveProject( resource );
            if ( project == null ) {
                return null;
            }

            //pom.xml and kmodule.xml are not inside packages
            if ( isPom( resource ) || kModuleService.isKModule( resource ) ) {
                return null;
            }

            return makePackage( project,
                                resource );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    private boolean hasKModule( final org.uberfire.java.nio.file.Path path ) {
        final org.uberfire.java.nio.file.Path kmodulePath = path.resolve( KMODULE_PATH );
        return Files.exists( kmodulePath );
    }

    private ProjectImports createProjectImports() {
        ProjectImports imports = new ProjectImports();
        imports.getImports().addImport( new Import( "java.lang.Number" ) );
        return imports;
    }
}

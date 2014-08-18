package org.kie.workbench.common.screens.projecteditor.backend.server;

import java.util.Date;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.ContextNotActiveException;
import javax.inject.Inject;
import javax.inject.Named;

import org.guvnor.common.services.project.model.POM;
import org.guvnor.common.services.project.service.POMService;
import org.guvnor.common.services.shared.metadata.MetadataService;
import org.jboss.errai.bus.server.annotations.Service;
import org.jboss.errai.security.shared.api.identity.User;
import org.kie.workbench.common.screens.projecteditor.model.ProjectScreenModel;
import org.kie.workbench.common.screens.projecteditor.service.ProjectScreenService;
import org.kie.workbench.common.services.shared.kmodule.KModuleService;
import org.kie.workbench.common.services.shared.project.KieProject;
import org.kie.workbench.common.services.shared.project.KieProjectService;
import org.kie.workbench.common.services.shared.project.ProjectImportsService;
import org.kie.workbench.common.services.shared.validation.ValidationService;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.commons.validation.PortablePreconditions;
import org.uberfire.io.IOService;
import org.uberfire.java.nio.base.options.CommentedOption;
import org.uberfire.java.nio.file.FileSystem;
import org.uberfire.rpc.SessionInfo;

@Service
@ApplicationScoped
public class ProjectScreenServiceImpl
        implements ProjectScreenService {

    @Inject
    private POMService pomService;

    @Inject
    private KModuleService kModuleService;

    @Inject
    private KieProjectService projectService;

    @Inject
    private MetadataService metadataService;

    @Inject
    private ValidationService validationService;

    @Inject
    private ProjectImportsService importsService;

    @Inject
    private User identity;

    @Inject
    private SessionInfo sessionInfo;

    @Inject
    @Named("ioStrategy")
    private IOService ioService;

    @Override
    public ProjectScreenModel load( final Path pathToPom ) {
        ProjectScreenModel model = new ProjectScreenModel();

        model.setPOM( pomService.load( pathToPom ) );
        model.setPOMMetaData( metadataService.getMetadata( pathToPom ) );
        model.setPathToPOM( pathToPom );

        final KieProject project = projectService.resolveProject( pathToPom );

        Path pathToKModule = project.getKModuleXMLPath();
        model.setKModule( kModuleService.load( pathToKModule ) );
        model.setKModuleMetaData( metadataService.getMetadata( pathToKModule ) );
        model.setPathToKModule( pathToKModule );

        Path pathToProjectImports = project.getImportsPath();
        model.setProjectImports( importsService.load( pathToProjectImports ) );
        model.setProjectImportsMetaData( metadataService.getMetadata( pathToProjectImports ) );
        model.setPathToImports( pathToProjectImports );

        return model;
    }

    @Override
    public void save( final Path pathToPomXML,
                      final ProjectScreenModel model,
                      final String comment ) {
        final KieProject project = projectService.resolveProject( pathToPomXML );
        final FileSystem fs = Paths.convert( pathToPomXML ).getFileSystem();
        try {
            ioService.startBatch( fs, makeCommentedOption( comment ) );
            pomService.save( pathToPomXML,
                             model.getPOM(),
                             model.getPOMMetaData(),
                             comment );
            kModuleService.save( project.getKModuleXMLPath(),
                                 model.getKModule(),
                                 model.getKModuleMetaData(),
                                 comment );
            importsService.save( project.getImportsPath(),
                                 model.getProjectImports(),
                                 model.getProjectImportsMetaData(),
                                 comment );
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        } finally {
            ioService.endBatch();
        }
    }

    @Override
    public ProjectScreenModel rename( final Path pathToPomXML,
                                      final String newName,
                                      final String comment ) {
        return load( projectService.rename( pathToPomXML, newName, comment ) );
    }

    @Override
    public void delete( final Path pathToPomXML,
                        final String comment ) {
        projectService.delete( pathToPomXML, comment );
    }

    @Override
    public void copy( Path pathToPomXML,
                      String newName,
                      String comment ) {
        projectService.copy( pathToPomXML, newName, comment );
    }

    @Override
    public boolean validate( final POM pom ) {
        PortablePreconditions.checkNotNull( "pom",
                                            pom );
        final String name = pom.getName();
        final String groupId = pom.getGav().getGroupId();
        final String artifactId = pom.getGav().getArtifactId();
        final String version = pom.getGav().getVersion();

        final String[] groupIdComponents = ( groupId == null ? new String[]{ } : groupId.split( "\\.",
                                                                                                -1 ) );
        final String[] artifactIdComponents = ( artifactId == null ? new String[]{ } : artifactId.split( "\\.",
                                                                                                         -1 ) );

        final boolean validName = !( name == null || name.isEmpty() ) && validationService.isProjectNameValid( name );
        final boolean validGroupId = !( groupIdComponents.length == 0 || validationService.evaluateIdentifiers( groupIdComponents ).containsValue( Boolean.FALSE ) );
        final boolean validArtifactId = !( artifactIdComponents.length == 0 || validationService.evaluateArtifactIdentifiers( artifactIdComponents ).containsValue( Boolean.FALSE ) );
        final boolean validVersion = !( version == null || version.isEmpty() || !version.matches( "^[a-zA-Z0-9\\.\\-_]+$" ) );

        return validName && validGroupId && validArtifactId && validVersion;
    }

    private CommentedOption makeCommentedOption( final String commitMessage ) {
        final String name = getIdentityName();
        final Date when = new Date();
        final CommentedOption co = new CommentedOption( getSessionId(),
                                                        name,
                                                        null,
                                                        commitMessage,
                                                        when );
        return co;
    }

    protected String getIdentityName() {
        try {
            return identity.getIdentifier();
        } catch ( ContextNotActiveException e ) {
            return "unknown";
        }
    }

    protected String getSessionId() {
        try {
            return sessionInfo.getId();
        } catch ( ContextNotActiveException e ) {
            return "--";
        }
    }

}

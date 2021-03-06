package org.kie.workbench.common.services.refactoring.backend.server.query.findruleattributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.enterprise.inject.Instance;

import org.apache.lucene.analysis.Analyzer;
import org.junit.Test;
import org.kie.uberfire.metadata.backend.lucene.analyzer.FilenameAnalyzer;
import org.kie.workbench.common.services.refactoring.backend.server.BaseIndexingTest;
import org.kie.workbench.common.services.refactoring.backend.server.TestIndexer;
import org.kie.workbench.common.services.refactoring.backend.server.drl.TestDrlFileIndexer;
import org.kie.workbench.common.services.refactoring.backend.server.drl.TestDrlFileTypeDefinition;
import org.kie.workbench.common.services.refactoring.backend.server.indexing.RuleAttributeNameAnalyzer;
import org.kie.workbench.common.services.refactoring.backend.server.query.NamedQuery;
import org.kie.workbench.common.services.refactoring.backend.server.query.RefactoringQueryServiceImpl;
import org.kie.workbench.common.services.refactoring.backend.server.query.response.DefaultResponseBuilder;
import org.kie.workbench.common.services.refactoring.backend.server.query.response.ResponseBuilder;
import org.kie.workbench.common.services.refactoring.backend.server.query.standard.FindRuleAttributesQuery;
import org.kie.workbench.common.services.refactoring.model.index.terms.ProjectRootPathIndexTerm;
import org.kie.workbench.common.services.refactoring.model.index.terms.RuleAttributeIndexTerm;
import org.kie.workbench.common.services.refactoring.model.index.terms.valueterms.ValueIndexTerm;
import org.kie.workbench.common.services.refactoring.model.index.terms.valueterms.ValueRuleIndexTerm;
import org.kie.workbench.common.services.refactoring.model.index.terms.valueterms.ValueTypeIndexTerm;
import org.kie.workbench.common.services.refactoring.model.query.RefactoringPageRequest;
import org.kie.workbench.common.services.refactoring.model.query.RefactoringPageRow;
import org.uberfire.java.nio.file.Path;
import org.uberfire.paging.PageResponse;

import static org.apache.lucene.util.Version.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FindRuleAttributesQueryInvalidIndexTermsTest extends BaseIndexingTest<TestDrlFileTypeDefinition> {

    private Set<NamedQuery> queries = new HashSet<NamedQuery>() {{
        add( new FindRuleAttributesQuery() {
            @Override
            public ResponseBuilder getResponseBuilder() {
                return new DefaultResponseBuilder( ioService() );
            }
        } );
    }};

    @Test
    public void testQueryInvalidIndexTerms() throws IOException, InterruptedException {
        final Instance<NamedQuery> namedQueriesProducer = mock( Instance.class );
        when( namedQueriesProducer.iterator() ).thenReturn( queries.iterator() );

        final RefactoringQueryServiceImpl service = new RefactoringQueryServiceImpl( getConfig(),
                                                                                     namedQueriesProducer );
        service.init();

        //Add test files
        final Path path1 = basePath.resolve( "drl1.drl" );
        final String drl1 = loadText( "drl1.drl" );
        ioService().write( path1,
                           drl1 );

        Thread.sleep( 5000 ); //wait for events to be consumed from jgit -> (notify changes -> watcher -> index) -> lucene index

        {
            final RefactoringPageRequest request = new RefactoringPageRequest( "FindRuleAttributesQuery",
                                                                               new HashSet<ValueIndexTerm>(),
                                                                               0,
                                                                               -1 );

            try {
                final PageResponse<RefactoringPageRow> response = service.query( request );
                fail();
            } catch ( IllegalArgumentException e ) {
                //Swallow. Expected
            }
        }

        {
            final RefactoringPageRequest request = new RefactoringPageRequest( "FindRuleAttributesQuery",
                                                                               new HashSet<ValueIndexTerm>() {{
                                                                                   add( new ValueTypeIndexTerm( "org.kie.workbench.common.services.refactoring.backend.server.drl.classes.Applicant" ) );
                                                                               }},
                                                                               0,
                                                                               -1 );

            try {
                final PageResponse<RefactoringPageRow> response = service.query( request );
                fail();
            } catch ( IllegalArgumentException e ) {
                //Swallow. Expected
            }
        }

        {
            final RefactoringPageRequest request = new RefactoringPageRequest( "FindRuleAttributesQuery",
                                                                               new HashSet<ValueIndexTerm>() {{
                                                                                   add( new ValueRuleIndexTerm( "myRule" ) );
                                                                                   add( new ValueTypeIndexTerm( "org.kie.workbench.common.services.refactoring.backend.server.drl.classes.Applicant" ) );
                                                                               }},
                                                                               0,
                                                                               -1 );

            try {
                final PageResponse<RefactoringPageRow> response = service.query( request );
                fail();
            } catch ( IllegalArgumentException e ) {
                //Swallow. Expected
            }
        }

    }

    @Override
    protected TestIndexer getIndexer() {
        return new TestDrlFileIndexer();
    }

    @Override
    public Map<String, Analyzer> getAnalyzers() {
        return new HashMap<String, Analyzer>() {{
            put( RuleAttributeIndexTerm.TERM,
                 new RuleAttributeNameAnalyzer( LUCENE_40 ) );
            put( ProjectRootPathIndexTerm.TERM,
                 new FilenameAnalyzer( LUCENE_40 ) );
        }};
    }

    @Override
    protected TestDrlFileTypeDefinition getResourceTypeDefinition() {
        return new TestDrlFileTypeDefinition();
    }

    @Override
    protected String getRepositoryName() {
        return this.getClass().getSimpleName();
    }

}

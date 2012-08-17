/*
This file is part of opensearch.
Copyright © 2009, Dansk Bibliotekscenter a/s,
Tempovej 7-11, DK-2750 Ballerup, Denmark. CVR: 15149043

opensearch is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

opensearch is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with opensearch.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.opensearch.fedora.search;

import org.fcrepo.server.search.Condition;
import org.fcrepo.server.search.Operator;
import org.fcrepo.server.Context;
import org.fcrepo.server.Server;
import org.fcrepo.server.errors.ServerException;
import org.fcrepo.server.search.FieldSearchQuery;
import org.fcrepo.server.search.ObjectFields;
import org.fcrepo.server.storage.DOReader;
import org.fcrepo.server.storage.RepositoryReader;
import org.fcrepo.server.storage.types.DatastreamXMLMetadata;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.LinkedList;

import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.store.RAMDirectory;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

// KULMULE
// NEW:
// Note: this should be moved into the other imports above
import org.apache.lucene.util.Version;;

/**
 *
 * @author stm
 */
public class FieldSearchResultLuceneTest
{

    LuceneFieldIndex indexer;
    @Mocked
    LuceneFieldIndex mockIndex;
    @Mocked
    RepositoryReader repoReader;
    @Mocked
    DOReader objectReader;
    @Mocked
    Server mockServer;
    @Mocked
    DatastreamXMLMetadata meta;
    @Mocked
    Server server;
    final static String dcxml = "<oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "<dc:title>demo</dc:title><pid>demo:1</pid><dcmDate>1970-01-01</dcmDate>"
            + "</oai_dc:dc>";
    final static String demo_pid = "demo:1";
    final static String demo_title = "demo";
    final static String demo_curtime = Long.toString( System.currentTimeMillis() );
    final static int maxResults = 10;
    final static int resultLifeTimeinSeconds = 10;

    @Before
    public void setUp() throws Exception
    {
        // KULMULE
        // OLD:
        // indexer = new LuceneFieldIndex( 1000L, new SimpleAnalyzer(), new RAMDirectory() );
        // NEW:
        indexer = new LuceneFieldIndex( 1000L, new SimpleAnalyzer( Version.LUCENE_35 ), new RAMDirectory() );
        // DONE
    }


    /**
     * Test of objectFieldsList method, of class FieldSearchResultLucene.
     */
    @Test
    public void testObjectFieldsList() throws Exception
    {
        setExpectationsForReaderWithMetadata();

        final String[] resultFields = getResultFields( "pid" );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, 1 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );


        final ObjectFields field = new ObjectFields( new String[]
                {
                    "pid"
                } );
        field.setPid( demo_pid );
        List<ObjectFields> expResult = new LinkedList<ObjectFields>();
        expResult.add( field );

        List<ObjectFields> result = resultInstance.objectFieldsList();

        assertEquals( expResult.get( 0 ).getPid(), result.get( 0 ).getPid() );
    }


    @Test
    public void testRetrivalOfFields() throws Exception
    {
        setExpectationsForReaderWithMetadata();

        final String[] resultFields = getResultFields( "pid", "dcmdate" );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, 1 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        final ObjectFields field = new ObjectFields( new String[]
                {
                    "pid", "title"
                } );
        field.setPid( demo_pid );
        field.setDCMDate( new Date( 1L ) );
        List<ObjectFields> expResult = new LinkedList<ObjectFields>();
        expResult.add( field );

        List<ObjectFields> result = resultInstance.objectFieldsList();

        assertEquals( expResult.get( 0 ).getPid(), result.get( 0 ).getPid() );
        assertEquals( expResult.get( 0 ).getDCMDate().toString(), result.get( 0 ).getDCMDate().toString() );
    }


    @Test
    public void testGetToken() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( Integer.toString( maxResults + 1 ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults + 1 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );
        FieldSearchResultLucene resultInstance2 = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        String tokenTwo = resultInstance2.getToken();
        String tokenOne = resultInstance.getToken();
        assertNotNull( String.format( "A search result with more hits than (%s) must have a token...", maxResults ), tokenOne );
        assertNotSame( "...and token must be unique even for identical searches", tokenOne, tokenTwo );
    }


    @Test
    public void testCursorForResultSetSmallerThanMaxResultDoesntExist() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( "pid" );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, 1 );

        FieldSearchResultLucene result = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );
        long expectedCursor = 0L;
        long cursor = result.getCursor();

        assertEquals( String.format( "Search results smaller than %s have no cursor", maxResults ), expectedCursor, cursor );
    }


    @Test
    public void testCursorForResultSetLargerThanMaxResultsExist() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( Integer.toString( maxResults + 1 ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults + 1 );

        FieldSearchResultLucene result = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );
        long cursor = result.getCursor();
	int length = result.objectFieldsList().size();

        assertEquals( 0, cursor ); 
	assertEquals( maxResults, length );
    }


    @Test
    public void testSearchResultsSmallerThanMaxResultsExpireInstantanouosly() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( "pid" );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults - 1 );

        FieldSearchResultLucene result = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        assertTrue( isExpired( result.getExpirationDate() ) );
    }


    @Test
    public void testSearchResultsLargerThanMaxResultsExpireLaterThanInstantly() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( Integer.toString( maxResults + 1 ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults + 1 );

        FieldSearchResultLucene largerResult = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        assertFalse( String.format( "Search results larger than %s expire %s seconds from now", maxResults, resultLifeTimeinSeconds ), isExpired( largerResult.getExpirationDate() ) );
    }


    @Test
    public void testSearchResultsLargerThanMaxResultsExpireInASetFuture() throws Exception
    {
        setExpectationsForReader();

        final String[] resultFields = getResultFields( Integer.toString( maxResults ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults - 1 );

        FieldSearchResultLucene largerResult = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        Date tenSecondsFromNow = new Date( System.currentTimeMillis() + resultLifeTimeinSeconds );
        String expectedResultApprox = tenSecondsFromNow.toString().substring( 0, 16 );
        String actualResultApprox = largerResult.getExpirationDate().toString().substring( 0, 16 );
        assertEquals( expectedResultApprox, actualResultApprox );
    }


    @Test
    public void testStep() throws Exception
    {
        setExpectationsForReaderWithMetadata();

        final String[] resultFields = getResultFields( Integer.toString( maxResults + 1 ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults + 1 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        assertEquals( "Result set is larger than `maxResults`, the instance will hold a pointer to the result set position", 0, resultInstance.getCursor() );
        resultInstance.stepAndCacheResult();
        assertEquals( "Resultset is exhausted after second pass, but cursor points to first element of second pass resultset", maxResults, resultInstance.getCursor() );

    }


    @Test
    public void testStepWhereSizeEqualsResultCounterAfterFirstStep() throws Exception
    {
        setExpectationsForReaderWithMetadata();

        final String[] resultFields = getResultFields( Integer.toString( maxResults * 2 ) );
        FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults * 2 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );

        assertEquals( "Result set is larger than `maxResults`, the instance will hold a pointer to the result set position", 0, resultInstance.getCursor() );
        resultInstance.stepAndCacheResult();
        assertEquals( "Resultset is exhausted after second pass, but cursor points to first element of second pass resultset", maxResults, resultInstance.getCursor() );
    }


    @Test
    public void testGetCompleteListSize() throws Exception
    {
        setExpectationsForReaderWithMetadata();

        final String[] resultFields = getResultFields( Integer.toString( maxResults + 1 ) );
        final FieldSearchQuery query = constructQuery();

        setExpectationsForIndexSearch( query, resultFields, maxResults + 1 );

        FieldSearchResultLucene resultInstance = new FieldSearchResultLucene( indexer, repoReader, resultFields, query, maxResults, resultLifeTimeinSeconds );
        assertEquals( maxResults + 1, resultInstance.getCompleteListSize() );
    }

    ////////////////////////////////////////////////////////////////////////////
    // Below follows test helper methods

    private Expectations setExpectationsForReader() throws ServerException
    {
        final InputStream metadata = new ByteArrayInputStream( dcxml.getBytes() );
        return new NonStrictExpectations()
        {


            {
                repoReader.getReader( anyBoolean, (Context) any, anyString );
                returns( objectReader );
                objectReader.GetDatastream( anyString, null );
                returns( (DatastreamXMLMetadata) any );
                meta.getContentStream();
                returns( metadata );
            }


        };
    }


    private Expectations setExpectationsForReaderWithMetadata() throws ServerException
    {
        final DatastreamXMLMetadata xmldatastream = new DatastreamXMLMetadata();
        xmldatastream.xmlContent = dcxml.getBytes();
        final InputStream metadata = new ByteArrayInputStream( dcxml.getBytes() );
        return new NonStrictExpectations()
        {


            {
                repoReader.getReader( anyBoolean, (Context) any, anyString );
                returns( objectReader );
                objectReader.GetDatastream( anyString, null );
                returns( xmldatastream );
                meta.getContentStream();
                returns( metadata );
            }


        };
    }


    private Expectations setExpectationsForIndexSearch( final FieldSearchQuery query, final String[] resultFields, int numberOfSearchResults ) throws Exception
    {
        final List<List<Pair<FedoraFieldName, String>>> indexResult = getNDemoSearchResults( numberOfSearchResults );
        return new NonStrictExpectations()
        {


            {
                mockIndex.search( query, resultFields );
                returns( indexResult );
            }


        };
    }


    private boolean isExpired( Date expirationDate )
    {
        if( null == expirationDate )
        {
            return true;
        }
        Date now = new Date( System.currentTimeMillis() );

        return expirationDate.before( now ) || expirationDate.equals( now );
    }


    private String[] getResultFields( String... fieldNames )
    {
        int numberOfDemoPids = 0;
        try
        {
            numberOfDemoPids = Integer.parseInt( fieldNames[0] );
        }
        catch( NumberFormatException nfe )
        {
        }

        if( numberOfDemoPids > 0 )
        {
            String[] resultFields = new String[numberOfDemoPids];
            for( int i = 0; i < numberOfDemoPids; i++ )
            {
                resultFields[i] = "demo:" + (i + 1);

            }
            return resultFields;
        }
        else
        {
            String[] resultFields = new String[fieldNames.length];
            System.arraycopy( fieldNames, 0, resultFields, 0, fieldNames.length );
            return resultFields;
        }
    }


    private List<List<Pair<FedoraFieldName, String>>> getNDemoSearchResults( int numberOfNs )
    {
        List<List<Pair<FedoraFieldName, String>>> searchResult = new LinkedList<List<Pair<FedoraFieldName, String>>>();
        List<Pair<FedoraFieldName, String>> result;
        for( int i = 0; i < numberOfNs; i++ )
        {
            result  = new LinkedList<Pair<FedoraFieldName, String>>();
            result.add( new Pair<FedoraFieldName, String>( FedoraFieldName.PID, "demo:" + (i + 1) ) );
            searchResult.add( result );
        }
        return searchResult;
    }


    private FieldSearchQuery constructQuery() throws Exception
    {
        List<Condition> conditions = new LinkedList<Condition>();
        Condition cond = new Condition( FedoraFieldName.TITLE.toString(), Operator.EQUALS, "demo" );

        conditions.add( cond );

        FieldSearchQuery query = new FieldSearchQuery( conditions );

        return query;
    }


}

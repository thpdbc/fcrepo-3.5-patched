package dk.dbc.opensearch.fedora.search;


import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WriteAheadLog extends WriteAheadLogStats
{
    private final static String LOG_OPEN_POSTFIX = ".log";
    private final static String LOG_COMITTING_POSTFIX = ".committing";
    private final static String LOG_NAME = "writeaheadlog";

    private final static String PID_FIELD_NAME = "pid";

    private static final Logger log = LoggerFactory.getLogger( WriteAheadLog.class );

    private final IndexWriter writer;

    private final File storageDirectory;

    private final boolean keepFileOpen;

    private boolean isOpen = false;

    private File currentFile;

    RandomAccessFile fileAccess = null;
    private ObjectName jmxObjectName;

    public WriteAheadLog( IndexWriter writer, File storageDirectory, int commitSize, boolean keepFileOpen ) throws IOException
    {
        super( commitSize );
        log.info( "Creating Write Ahead Log in directory {}, with commit size: {} and keepFileOpen: {}",
                new Object[] { storageDirectory.getAbsolutePath(), commitSize, keepFileOpen} );

        checkParameterForNullLogAndThrow( "writer", writer );
        checkParameterForNullLogAndThrow( "storageDirectory", storageDirectory );


        this.writer = writer;
        this.storageDirectory = storageDirectory;
        this.keepFileOpen = keepFileOpen;

        // Register the JMX monitoring bean
        try
        {
            jmxObjectName = new ObjectName( "FieldSearchLucene:name=WriteAheadLog" );
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean( this, jmxObjectName);
        }
        catch( JMException ex )
        {
            log.error( "Unable to register monitor. JMX Monitoring will be unavailable", ex);
        }

    }

    private void checkParameterForNullLogAndThrow( String name, Object value)
    {
        if ( value == null )
        {
            String error = String.format( "Parameter %s may not be null", name );
            log.error( error );
            throw new NullPointerException( error );
        }
    }

    public void initialize() throws IOException
    {
        log.debug( "Initializing Write Ahead Log" );
        if ( storageDirectory.listFiles().length > 0 )
        {
            recoverUncomittedFiles();
        }
        currentFile = createNewFile();
        isOpen = true;
    }


    void recoverUncomittedFiles( ) throws IOException
    {
        File comittingFile = new File( storageDirectory, LOG_NAME + LOG_COMITTING_POSTFIX );
        File logFile = new File( storageDirectory, LOG_NAME + LOG_OPEN_POSTFIX );

        if ( comittingFile.exists() )
        {
            recoverUncomittedFile( comittingFile, writer );
            comittingFile.delete();
        }
        if ( logFile.exists() )
        {
            recoverUncomittedFile( logFile, writer );
            logFile.delete();
        }
    }


    static int recoverUncomittedFile( File walFile, IndexWriter writer ) throws IOException
    {
        log.warn( "Recovering file {}", walFile );

        RandomAccessFile fileToRecover = new RandomAccessFile( walFile, "r" );

        int count = 0;

        try
        {
            while ( true )
            {
                DocumentData docData = WriteAheadLog.readDocumentData( fileToRecover );
                count++;
                Term pidTerm = getPidTerm( docData.pid );

                if ( docData.docOrNull == null )
                {
                    log.info( "Recovering deleted document for {}", docData.pid );
                    writer.deleteDocuments( pidTerm );
                }
                else
                {
                    log.info( "Recovering updated document for {}", docData.pid );
                    writer.updateDocument( pidTerm, docData.docOrNull );
                }
            }
        }
        catch( IOException ex )
        {
            log.debug( "No more updates found in log file {}", walFile);
        }
        finally
        {
            fileToRecover.close();
            writer.commit();
        }
        log.info( "Recovered {} changes from log file {}", count, walFile);
        return count;
    }


    public void deleteDocument( String pid) throws IOException
    {
        tryUpdateOrDeleteDocument( pid, null );
    }

    public void updateDocument( String pid, Document doc ) throws IOException
    {
        tryUpdateOrDeleteDocument( pid, doc );
    }

    private void tryUpdateOrDeleteDocument( String pid, Document docOrNull ) throws IOException
    {
        try
        {
            updateOrDeleteDocument( pid, docOrNull );
        }
        catch ( IOException ex )
        {
            fileAccess = null;
            throw ex;
        }
    }

    private void updateOrDeleteDocument( String pid, Document docOrNull ) throws IOException
    {
        long updateStart = System.nanoTime();

        File commitFile = null;
        numberOfUncomittedDocuments.incrementAndGet();
        int updates = numberOfUpdatedDocuments.incrementAndGet();
        byte[] recordBytes = createDocumentData( pid, docOrNull );

        synchronized ( this )
        {
            if ( !isOpen )
            {
                throw new IOException( "Write Ahead Log is not open");
            }

            writeDocumentToFile( recordBytes );

            if ( updates % commitSize == 0)
            {
                // Time to commit. Close existing log file, rename and create a new log file
                if ( fileAccess != null )
                {
                    try
                    {
                        fileAccess.close();
                    }
                    finally
                    {
                        fileAccess = null;
                    }
                }
                commitFile = new File( storageDirectory, LOG_NAME + LOG_COMITTING_POSTFIX );
                currentFile.renameTo( commitFile );
                currentFile = createNewFile();
            }
        }

        updateInWriter( pid, docOrNull );

        if ( commitFile != null )
        {
            commitWriter();
            // Erase old file
            commitFile.delete();
        }
        long updateEnd = System.nanoTime();

        totalUpdateTimeMicroS.addAndGet( (updateEnd - updateStart)/1000 );
    }


    private void commitWriter() throws IOException
    {
        long commitStart = System.nanoTime();
        log.info( "Comitting {} documents.", numberOfUncomittedDocuments );
        numberOfCommits.incrementAndGet();
        numberOfUncomittedDocuments.set( 0 );
        // commit
        writer.commit();
        long commitEnd = System.nanoTime();
        totalCommitToLuceneTimeMicroS.addAndGet( (commitEnd - commitStart)/1000 );
    }


    private void updateInWriter( String pid, Document doc ) throws IOException
    {
        long updateStart = System.nanoTime();
        Term pidTerm = getPidTerm( pid );
        if ( doc == null )
        {
            log.debug( "Deleting document with PID {}", pid );
            this.writer.deleteDocuments( pidTerm);
        }
        else
        {
            log.debug( "Updating document with PID {}", pid );
            this.writer.updateDocument( pidTerm, doc );
        }
        long updateEnd = System.nanoTime();
        totalUpdateInLuceneTimeMicroS.addAndGet( (updateEnd - updateStart)/1000 );
    }

    public synchronized void shutdown() throws IOException
    {
        log.info( "Shutting down Write Ahead Log");
        isOpen = false;

        commitWriter();

        log.info( "Added {} documents. Comitted {} times", numberOfUpdatedDocuments, numberOfCommits );
        if ( currentFile != null )
        {
            if ( fileAccess != null )
            {
                fileAccess.close();
                fileAccess = null;
            }
            currentFile.delete();
            currentFile = null;
        }
        if ( jmxObjectName != null )
        {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            try
            {
                server.unregisterMBean( jmxObjectName );
            }
            catch( JMException ex )
            {
                log.warn( "Failed to deregister jmx bean", ex);
            }
        }
    }

    private File createNewFile()
    {
        return new File( storageDirectory, LOG_NAME + LOG_OPEN_POSTFIX );
    }

    private RandomAccessFile getFileAccess() throws IOException
    {
        if ( fileAccess == null )
        {
            fileAccess = new RandomAccessFile( currentFile, "rwd" );
            fileAccess.seek( currentFile.length() );
        }
        return fileAccess;
    }

    private void releaseFileAccess() throws IOException
    {
        if ( ! keepFileOpen )
        {
            fileAccess.close();
            fileAccess = null;
        }
    }

    private void writeDocumentToFile( byte[] recordBytes ) throws IOException
    {
        long writeStart = System.nanoTime();
        try
        {
            writeDocumentData( getFileAccess(), recordBytes );
        }
        finally
        {
            releaseFileAccess();
        }
        long writeEnd = System.nanoTime();
        totalWriteToFileTimeMicroS.addAndGet( (writeEnd - writeStart)/1000 );
    }

    private static byte[] createDocumentData( String pid, Document docOrNull ) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream( bos );
        out.writeObject( pid );
        out.writeObject( docOrNull );
        byte[] recordBytes= bos.toByteArray();
        out.reset();
        return recordBytes;
    }

    static void writeDocumentData( RandomAccessFile raf, String pid, Document docOrNull ) throws IOException
    {
        byte[] recordBytes = createDocumentData( pid, docOrNull );

        writeDocumentData( raf, recordBytes );
    }

    static void writeDocumentData( RandomAccessFile raf, byte[] recordBytes ) throws IOException
    {
        ByteBuffer rbb = ByteBuffer.wrap(recordBytes);

        raf.getChannel().write( rbb );
    }

    static DocumentData readDocumentData( RandomAccessFile raf ) throws IOException
    {
        try
        {
            ObjectInputStream istr = new ObjectInputStream( Channels.newInputStream( raf.getChannel() ) );

            String pid = ( String ) istr.readObject();
            Document doc = ( Document ) istr.readObject();
            return new DocumentData( pid, doc );
        }
        catch ( ClassNotFoundException ex )
        {
            throw new IOException( ex );
        }
    }


    static Term getPidTerm( String pid )
    {
        Term pidTerm = new Term( PID_FIELD_NAME, pid );
        return pidTerm;
    }


    static class DocumentData
    {
        final String pid;
        final Document docOrNull;

        public DocumentData( String pid, Document docOrNull )
        {
            this.pid = pid;
            this.docOrNull = docOrNull;
        }

        public String toString()
        {
            return "PID '" + pid + "' Document: " + docOrNull;
        }
    }

}
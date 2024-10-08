package org.dbpedia.extraction.server.resources

import java.net.{URI, URL}

import org.dbpedia.extraction.destinations.formatters.{RDFJSONFormatter, TerseFormatter}
import org.dbpedia.extraction.util.Language
import javax.ws.rs._
import javax.ws.rs.core.{Context, HttpHeaders, MediaType, Response}
import java.util.logging.{Level, Logger}

import scala.xml.Elem
import scala.io.{Codec, Source}
import org.dbpedia.extraction.server.Server
import org.dbpedia.extraction.wikiparser.WikiTitle
import org.dbpedia.extraction.destinations.{DeduplicatingDestination, WriterDestination}
import org.dbpedia.extraction.sources.{WikiSource, XMLSource}
import stylesheets.TriX
import java.io.StringWriter

object Extraction
{
    private val logger = Logger.getLogger(getClass.getName)
      
    val lines : Map[String, String] = {
        val file = "/extractionPageTitles.txt"
        try {
            // ugly - returns null if file not found, which leads to NPE later
            val in = getClass.getResourceAsStream(file)
            try {
                val titles = 
                  for (line <- Source.fromInputStream(in)(Codec.UTF8).getLines 
                    if line.startsWith("[[") && line.endsWith("]]") && line.contains(':')
                  ) yield {
                    val colon = line.indexOf(':')
                    (line.substring(2, colon), line.substring(colon + 1, line.length - 2))
                  }
                titles.toMap
            } 
            finally in.close
        }
        catch { 
            case e : Exception =>
                logger.log(Level.WARNING, "could not load extraction page titles from classpath resource "+file, e)
                Map()
        }
    }
}

/**
 * TODO: merge Extraction.scala and Mappings.scala
 */
@Path("/extraction/{lang}/")
class Extraction(@PathParam("lang") langCode : String)
{
    private val language = Language.getOrElse(langCode, throw new WebApplicationException(new Exception("invalid language "+langCode), 404))

    if(!Server.instance.managers.contains(language))
        throw new WebApplicationException(new Exception("language "+langCode+" not configured in server"), 404)
    
    private def getTitle : String = Extraction.lines.getOrElse(langCode, "Berlin")

    @GET
    @Produces(Array("application/xhtml+xml"))
    def get = 
    {
       <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
         {ServerHeader.getHeader("Extractor a page")}
         <body>
           <div class="row">
             <div class="col-md-3 col-md-offset-5">
              <h2>Extract a page</h2>
               <form action="extract" method="get">
                Page title<br/>
                <input type="text" name="title" value={ getTitle }/><br/>
                Revision ID (optional, overrides title)<br/>
                <input type="text" name="revid"/><br/>
                Output format<br/>
                <select name="format">
                  <option value="trix">Trix</option>
                  <option value="turtle-triples">Turtle-Triples</option>
                  <option value="turtle-quads">Turtle-Quads</option>
                  <option value="n-triples">N-Triples</option>
                  <option value="n-quads">N-Quads</option>
                  <option value="rdf-json">RDF/JSON</option>
                </select><br/>
                <select name="extractors">
                <option value="mappings">Mappings Only</option>
                <option value="custom">All Enabled Extractors </option>
                </select><br/>
              <input type="submit" value="Extract" />
            </form>
            </div>
           </div>
         </body>
       </html>
    }

    /**
     * Extracts a MediaWiki article
     */
    @GET
    @Path("extract")
    def extract(@QueryParam("title") title: String, @QueryParam("revid") @DefaultValue("-1") revid: Long, @QueryParam("format") format: String, @QueryParam("extractors") extractors: String, @Context headers : HttpHeaders) : Response =
    {
      import scala.collection.JavaConverters._
      import scala.collection.JavaConversions._
        if (title == null && revid < 0) throw new WebApplicationException(new Exception("title or revid must be given"), Response.Status.NOT_FOUND)

      val requestedTypesList = headers.getAcceptableMediaTypes.map(_.toString)
      val browserMode = requestedTypesList.isEmpty || requestedTypesList.contains("text/html") || requestedTypesList.contains("application/xhtml+xml") || requestedTypesList.contains("text/plain")

        val writer = new StringWriter

        var finalFormat = format
        val acceptContentBest = requestedTypesList.map(selectFormatByContentType).head

        if (!acceptContentBest.equalsIgnoreCase("unknownAcceptFormat") && !browserMode)
          finalFormat = acceptContentBest
        val contentType = if (browserMode) selectInBrowserContentType(finalFormat) else selectContentType(finalFormat)

        val formatter = finalFormat match
        {
            case "turtle-triples" => new TerseFormatter(false, true)
            case "turtle-quads" => new TerseFormatter(true, true)
            case "n-triples" => new TerseFormatter(false, false)
            case "n-quads" => new TerseFormatter(true, false)
            case "rdf-json" => new RDFJSONFormatter()
            case _ => TriX.writeHeader(writer, 2)
        }

      val customExtraction = extractors match
      {
        case "mappings" => false
        case "custom" => true
        case _ => false
      }

        val source = 
          if (revid >= 0) WikiSource.fromRevisionIDs(List(revid), new URL(language.apiUri), language)
          else WikiSource.fromTitles(List(WikiTitle.parse(title, language)), new URL(language.apiUri), language)

        // See https://github.com/dbpedia/extraction-framework/issues/144
        // We should mimic the extraction framework behavior
        val destination = new DeduplicatingDestination(new WriterDestination(() => writer, formatter))
        Server.instance.extractor.extract(source, destination, language, customExtraction)

        Response.ok(writer.toString)
          .header(HttpHeaders.CONTENT_TYPE, contentType +"; charset=UTF-8" )
          .build()
    }

  // map
  private def selectFormatByContentType(format: String): String = {

    (format match
    {
      case "text/xml" => "trix"
      case "text/turtle" => "turtle-triples"
      //case "text/nquads" => "turtle-quads" // this does not exist as mimetype
      case "application/n-triples" => "n-triples"
      case "application/n-quads" => "n-quads"
      case MediaType.APPLICATION_JSON => "rdf-json"
      //case "application/ld+json" => MediaType.APPLICATION_JSON
      case _ => "unknownAcceptFormat"
    })
  }

  // override content type in browser for some formats to display text instead of downloading a file, or
  private def selectInBrowserContentType(format: String): String = {

    format match
    {
      case "trix" => MediaType.APPLICATION_XML
      case "rdf-json" => MediaType.APPLICATION_JSON
      case _ => MediaType.TEXT_PLAIN
    }
  }

  // map format parameters to regular content types
    private def selectContentType(format: String): String = {

      format match
      {
        case "trix" => MediaType.APPLICATION_XML
        case "turtle-triples" => "text/turtle"
        case "turtle-quads" => "text/nquads"
        case "n-triples" => "application/n-triples"
        case "n-quads" => "application/n-quads"
        case "rdf-json" => MediaType.APPLICATION_JSON
        case "trix" => MediaType.APPLICATION_XML
        case _ => MediaType.TEXT_PLAIN
      }
    }

    /**
     * Extracts a MediaWiki article
     * @Consumes(Array("application/xml"))
     * @Produces(Array("application/n-triples"))
     */
    @POST
    @Path("extract")
    @Consumes(Array("application/xml"))
    def extract(xml : Elem, @QueryParam("format") format: String, @Context headers : HttpHeaders) : Response =
    {
      val writer = new StringWriter

      val contentType = selectContentType(format)

      val formatter = format match
      {
        case "turtle-triples" => new TerseFormatter(false, true)
        case "turtle-quads" => new TerseFormatter(true, true)
        case "n-triples" => new TerseFormatter(false, false)
        case "n-quads" => new TerseFormatter(true, false)
        case "rdf-json" => new RDFJSONFormatter()
        case _ => TriX.writeHeader(writer, 2)
      }

      val source = XMLSource.fromXML(xml, language)

      val destination = new WriterDestination(() => writer, formatter)

      Server.instance.extractor.extract(source, destination, language, true)

      Response.ok(writer.toString)
        .header(HttpHeaders.CONTENT_TYPE, contentType +"; charset=UTF-8" )
        .build()
    }
}

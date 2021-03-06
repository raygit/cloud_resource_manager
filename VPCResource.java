// JDK refs.
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.logging.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import org.w3c.dom.*;
import org.w3c.dom.ls.*;

// Third party refs.
import org.custommonkey.xmlunit.*;
import org.restlet.*;
import org.restlet.data.*;
import org.restlet.representation.*;
import org.restlet.resource.*;
import org.restlet.service.*;
import org.restlet.ext.xml.*;
import com.mongodb.*;

/**
 * A Restlet interfacing between MOAB and the Front-End
 * @see ResourceManager for the URI mappings
 * @author Raymond Tay
 * @version 1.0
 */
public class VPCResource extends ServerResource {

	private HashSet<String> allowedNodes = null;

	//  only valid in this translation unit
	private enum STATUS { SUCCESS , FAIL };

	protected void doInit() {
		super.doInit();
		allowedNodes = new HashSet<String>();
		// the following names of the nodes in the VPC config xml
		// will be added to this read-only data structure.
		allowedNodes.add("cores");
		allowedNodes.add("memory");
		allowedNodes.add("disk");
		getLogger().log(Level.INFO, "VPCResource initialized and ready to go.");
	}


	/**
	 * Response with a XML document when a matching
	 * project_id is detected
	 * @param project_id - the project's identification string passed in via the HTTP request object
	 * @return a XML document representing the VPC matched by the project_id
	 */
	@Get("xml")
	public Representation getVPCconfig() throws Exception {
		// extract the data from the database (MongoDB) and return it 
		// as a XML 
		BasicDBObject project = null;
		Document projectXml = null;

		String id = (String)getRequestAttributes().get("project_id");
		getLogger().log(Level.INFO, "REQ_VPC_CONFIG => PROJECT ID:" + id);

		Mongo m = new Mongo();
		DB db = m.getDB("vpc");
		DBCollection col = db.getCollection("configuration");
		DBCursor cursor = col.find(new BasicDBObject("project_id", id));

		if (cursor.hasNext()) { // expecting to find just 1 document
			project = (BasicDBObject)cursor.next();
			
			// get the XML file
			DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = fac.newDocumentBuilder();
			projectXml = builder.parse(new InputSource(new StringReader( (String)project.get("config_file") )));
		} else {
			// there's an error here likely, the client should know
			// with a more descriptive message since we're in the
			// pre-production stage
			getResponse().setStatus(org.restlet.data.Status.CLIENT_ERROR_NOT_FOUND, "The passed in project_id=" + id + " does not exist" + 
							" OR you must have missed the project_id you've passed in u twit");
			getLogger().log(Level.WARNING, "REQ_VPC_CONFIG => PROJECT ID:" + id + " does not exist in the database");
		}	

		// At this point, the resetlet should return a valid XML doc
		// to the requester
		DomRepresentation domModel = new DomRepresentation();
		domModel.setDocument(projectXml);

		return domModel;
	}

	/**
	 *
	 * @param project_id - the project's identification string passed in via the http request obj
	 * @param xml - xml document passed in representing the new vpc
	 * @return HTTP OK - remember this is just a submission request ; actual work happens async
	 * 					you can use @see getVPCStatus() to check out the status
	 */
	@Post
	public void provision(Representation xml) throws Exception {

		// Use restlet's representation of the DOM and process it
		// As far as restlet 2.0's concern, it should be w3c compliant
		Document passedinXml = null;
		try {
			DomRepresentation domModel = new DomRepresentation(xml);
			passedinXml = domModel.getDocument();
		} catch (IOException ioe) { 
			ioe.printStackTrace(); 
			// the twit passed in a non-conforming xml, return error
			getResponse().setStatus(org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST, "You tried to fool me with a wrong XML. Bad bad bad");
			getLogger().log(Level.WARNING, "REQ_VPC_PROVISION => PROJECT ID:" + (String)getRequestAttributes().get("project_id") + " has a malformed XML");
		} 

		String vpcconfig = getStringFromDOM(passedinXml);

		BasicDBObject project = null;
		Mongo m = new Mongo();
		String id = (String)getRequestAttributes().get("project_id");
		DB db = m.getDB("vpc");
		DBCollection col = db.getCollection("configuration");
		DBCursor cursor = col.find(new BasicDBObject("project_id", id));

		// if a record exists, it implies that the provision request has been 
		// received prior so we update the database with the latest VPC XML
		// configuration
		if (cursor.hasNext()) { // expecting to find just 1 document
			project = (BasicDBObject)cursor.next();
			BasicDBObject newRec = new BasicDBObject();

			// Discover what has changed between the previous and current(intended) 
			// VPC configuration
			DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = fac.newDocumentBuilder();
			DocumentBuilder builder2 = fac.newDocumentBuilder();

			Document orgXml = builder.parse( new InputSource(new StringReader( (String)project.get("config_file") )));
			Document newXml = builder2.parse( new InputSource(new StringReader( vpcconfig )));

			DetailedDiff diff = new DetailedDiff(new Diff( (String)project.get("config_file"), vpcconfig));
			List<Difference> all = diff.getAllDifferences();

			// Validate that the differences are allowed
			STATUS isCorrect = validateVPCChanges(all, newXml);

			if (isCorrect == STATUS.FAIL) 
					getResponse().setStatus(org.restlet.data.Status.CLIENT_ERROR_CONFLICT);

			HashMap<java.lang.String, org.w3c.dom.Node> map = new HashMap<String, Node> ();
			updateVPCAsync(id, map);

			newRec.put("project_id", project.get("project_id"));
			newRec.put("config_file", vpcconfig); // replace the original with the latest
			newRec.put("moab_web_rsrv_id", project.get("moab_web_rsrv_id"));
			newRec.put("moab_app_rsrv_id", project.get("moab_app_rsrv_id"));
			newRec.put("moab_db_rsrv_id",  project.get("moab_db_rsrv_id"));
			col.update(project, newRec);
	
		} else {

			// Fire the right squence of MOAB commands
			// There should in general 2 actions here.
			// 1) Conduct a reservation in dry-run mode
			// 2) Upon retrieval of the MOAB OID, then we'll conduct the actual reservation

			// 27 October: TODO: The following 3 statements will be skipped for now
			// String webMoabId = executeReservationDryRun(DomHelper.TIER_TYPE.WEB, id);
			// String appMoabId = executeReservationDryRun(DomHelper.TIER_TYPE.APP, id);
			// String dbMoabId = executeReservationDryRun(DomHelper.TIER_TYPE.DB, id);

			// We'll fire the actual reservation here
			// Update the database if applicable 
			ArrayList< HashMap<String,String> > webMoabIds = executeReservation(DomHelper.TIER_TYPE.WEB, id, passedinXml);
			ArrayList< HashMap<String,String> > appMoabIds = executeReservation(DomHelper.TIER_TYPE.APP, id, passedinXml);
			ArrayList< HashMap<String,String> > dbMoabIds = executeReservation(DomHelper.TIER_TYPE.DB, id, passedinXml);

			BasicDBObject rec = new BasicDBObject();
			rec.put("project_id", id);
			rec.put("config_file", vpcconfig); 
			rec.put("moab_web_rsrv_id", webMoabIds);
			rec.put("moab_app_rsrv_id", appMoabIds);
			rec.put("moab_db_rsrv_id", dbMoabIds);
			col.insert(rec);
		}

		// The HTTP OK will be returned to signify that the reservation
		// request to MOAB has been issued w/o faults. *fingers crossed*
		HashMap attr = new HashMap<String, Object>();
		attr.put("project_id", id);
		getResponse().setAttributes(attr);
		getResponse().setStatus(org.restlet.data.Status.SUCCESS_OK);
	}

	/**
	 * Validates the issued changes to the VPC
	 *
	 * Current supported validation is as follows:
	 * 1) Add/Remove CPU(s)
	 * 2) Add/Remove RAM
	 * 3) Add Disk(s)
	 *
	 * @param list - list of differences found by comparing between XMLs
	 * @return a SUCCESS / FAIL status
	 */
	private STATUS validateVPCChanges(List<Difference> list, org.w3c.dom.Document newXml ) {
		XPath xpath = XPathFactory.newInstance().newXPath();

		for( Difference d : list ) {
			try {
				Node node = (Node) xpath.evaluate(d.getTestNodeDetail().getXpathLocation(),newXml, XPathConstants.NODE);

				if (allowedNodes.contains(node.getNodeName())) {
					if (node.getNodeName().equalsIgnoreCase("cores")) {

						Node parent = node.getParentNode().getParentNode().getParentNode(); // go up 3 levels
						String tier = parent.getNodeName(); // 'tier' is either "compartmentWeb", "compartmentApp", "compartmentDB"
						// once the tier is found, we can fire the proper MOAB
						// command to enforce the changes
						int cores = Integer.parseInt( node.getTextContent() );

					} else if (node.getNodeName().equalsIgnoreCase("memory")) {

						Node parent = node.getParentNode().getParentNode().getParentNode(); // go up 3 levels
						// once the tier is found, we can fire the proper MOAB
						// command to enforce the changes
						long cores = Long.parseLong( node.getTextContent() );

					} else if (node.getNodeName().equalsIgnoreCase("disk")) {
					} else { 
							// Obviously, a requested modification to the VPC 
							// is not detected ... so what should we do? fail it
							// or succeed for those that are proper?
							//
					}
				} else continue;


			} catch (XPathExpressionException exec ) {
				exec.printStackTrace();
				return STATUS.FAIL;
			}
		}

		return STATUS.SUCCESS;
	}

	/**
	 * This will fire a series of MOAB commands in the background.
	 * The idea is to allow the client to poll the outcomes of the MOAB
	 * commands w/o burdening this restlet. UX experience is important!
	 *
	 * @param projectId - the string representation e.g. "BCA_INTERNAL_001"
	 * @param nodes     - the list of nodes to be updated and it'll looked something like this
	 *              \<WEB, <resource><cores>4</cores></resource>\>
	 */
	private void updateVPCAsync(String projectId, Map<java.lang.String, org.w3c.dom.Node> nodes) {
		TaskService taskService = getApplication().getTaskService();

		// figure out which tier in the VPC needs to be re-configured
	}

	/**
	 * The special thing about conducting the dryrun is that it should not be done 
	 * in a async manner since the caller really needs the MOAB issued OID 
	 * for the actual reservation
	 * @param type - indicating either WEB, APP or DB
	 * @param projectId - project's identification string
	 */
	private String executeReservationDryRun(DomHelper.TIER_TYPE type, String projectId) {
		String id = "";
		switch (type) {
				case WEB:	
				case APP:	
				case DB:	
					try {
						ProcessBuilder pb = new ProcessBuilder("./reservationDryRun.sh", projectId, type.toString());
						// we can put in any ENV variables we want the shell script to understand
						Process proc = pb.start();	
						BufferedInputStream bis = new BufferedInputStream(proc.getInputStream());
						byte[] data = new byte[256]; // TODO: bad idea to have static arrays.
						bis.read(data, 0, 255);
						id = (new String(data)).trim();
						System.out.println(id);
					} catch (IOException ioe) { ioe.printStackTrace(); }
		}
		return id;
	}

	/**
	 * This should be async to prevent bogging down the Restlet
	 * from serving other incoming requests.
	 * 
	 * The invocation of MOAB commands to make the reservation/
	 * provision is based on tiers. This expects the provisioning
	 * to complete within 3 minutes else a timeout occurs
	 *
	 * @param OID - MOAB reservation id
	 * @param projectId - project's identification string
	 * @return the MOAB id or 0 for failure
	 */
	private ArrayList< HashMap<String,String> > executeReservation(DomHelper.TIER_TYPE type, String projectId, org.w3c.dom.Document xml) {
		Document xmlDoc = null;
		ArrayList< HashMap<String, String> > ids = new ArrayList< HashMap<String, String> >();
		switch (type) {
				case WEB:	
				case APP:	
				case DB:	
					try {
						String id = "";
						// TODO: Re-factor code off to async codelets
						CreateVmBuilder vmBuilder = new CreateVmBuilder(new DomHelper(xml), projectId, type);
						
						// commandStrings - contains a list of command Strings for firing in MOAB
						Vector< HashMap<String, String> > commandStrings = vmBuilder.getCommandStrings();

						// each member of the list is a k,v pair s.t. key = hostname, value = command line args
						// passed to MOAB/HPOO.
						for( HashMap<String, String> kvPair : commandStrings) {
							Set<String> keys = kvPair.keySet();
							for( String key : keys )  {
								Future< Vector<String> > f = getApplication().getTaskService().submit(new ProvisionVMTask(key, kvPair.get(key)));
								Vector<String> fkvPair = (Vector<String>) f.get(3, TimeUnit.MINUTES);
								HashMap<String,String> map = new HashMap<String,String>();
								map.put(fkvPair.get(0), fkvPair.get(1));
								ids.add( map );
							}
						}
					} catch (InterruptedException ie ) { ie.printStackTrace(); }
					  catch (ExecutionException ee)    { ee.printStackTrace(); }
					  catch (TimeoutException te)    { te.printStackTrace(); }
		}
		return ids;
	}
	/**
	 * This private implementation is from stackoverflow.com
	 * and i claim no credit for it. In case you wanna know the
	 * context, here's the link 
	 * http://stackoverflow.com/questions/315517/is-there-a-more-elegant-way-to-convert-an-xml-document-to-a-string-in-java-than
	 * @param doc - a XML comformant Document object
	 * @return A string representation of the XML object
	 */
	private String getStringFromDOM(org.w3c.dom.Document doc) {
		try {
			DOMSource domSource = new DOMSource(doc);
			StringWriter writer = new StringWriter();
			StreamResult result = new StreamResult(writer);
			TransformerFactory fac = TransformerFactory.newInstance();
			javax.xml.transform.Transformer transformer = fac.newTransformer();
			transformer.transform(domSource, result);
			writer.flush();
			return writer.toString();
		} catch (TransformerException ex) {
			ex.printStackTrace();
			return "";
		}
	}

}

/**
 * This task represents the call to the OO
 * via the "createvm.sh" script with accepting parameters
 */
class ProvisionVMTask implements Callable< Vector<String> >  {
	private String hostId;
	private String args;
	private String id;
	protected ProvisionVMTask(String hostId, String args) {
		this.hostId = hostId;
		this.args = args;
		this.id = "";
	}	
	@Override
	public Vector<String> call() throws Exception {
		ProcessBuilder pb = new ProcessBuilder("./createvm.sh", args);
	
		// we can put in any ENV variables we want the shell script to understand
		Process proc = pb.start();	
		BufferedInputStream bis = new BufferedInputStream(proc.getInputStream());
		byte[] data = new byte[1024]; // TODO: bad idea to have static arrays.
		bis.read(data, 0, 1023);
		id = (new String(data)).trim(); // this trimming is necessary otherwise you'll get the "Content not allowed in trailing section."
		DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = fac.newDocumentBuilder();

		Document xmlDoc = builder.parse(new InputSource(new StringReader(id)));  
		XPath xpath = XPathFactory.newInstance().newXPath();
		String status = (String) xpath.evaluate("//request/status/text()", xmlDoc, XPathConstants.STRING);
		if ( status.equalsIgnoreCase("ok") ) 
			id = (String) xpath.evaluate("//request/id/text()", xmlDoc, XPathConstants.STRING);
		else id = "0"; 

		proc.destroy();
		Vector<String> v = new Vector<String>();
		v.add(0,hostId);
		v.add(1,id);
		return v;
	}
}


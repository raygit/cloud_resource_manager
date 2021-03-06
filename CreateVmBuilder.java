import java.util.*;
import java.text.*;

import org.antlr.stringtemplate.*;

/*
 * This class uses an open source library known as StringTemplates
 * and is one of my favourites.
 *
 * @author Raymond Tay
 * @version 1.0
 */
public class CreateVmBuilder {
		/**
		 * CAUTION:
		 *
		 * StringTemplates cache the values of the attributes once they're 
		 * set so you need to invoke reset() on each template after use
		 */
		private StringTemplate hostname = new StringTemplate(" -n $hostname$ ");
		private StringTemplate cores    = new StringTemplate(" -c $cores$ ");
		private StringTemplate memory   = new StringTemplate(" -m $memory$ ");
		private StringTemplate bootDisk = new StringTemplate(" -bd $type$,$size$ ");
		private StringTemplate dataDisk = new StringTemplate(" -dd $type$,$size$ ");
		private StringTemplate os       = new StringTemplate(" -o $os$ ");
		private StringTemplate app      = new StringTemplate(" -a $app$ ");
		private StringTemplate zone     = new StringTemplate(" -z $zone$ ");
		private StringTemplate start    = new StringTemplate(" -st $starttime$ ");
		private StringTemplate duration = new StringTemplate(" -d $duration$ ");
		private DomHelper dom;
		private DomHelper.TIER_TYPE tier;
		private String projectId;
		private Format formatter;
		private String hostId;

		public CreateVmBuilder(DomHelper dom, String projectId, DomHelper.TIER_TYPE tier) {
			this.dom = dom;
			this.tier = tier;
			this.projectId = projectId;
			this.formatter = new SimpleDateFormat("HH:mm:ss_MM/dd/yyyy");
		}

		// its either i do this computation in the 
		// MachineBuilder class or over here 
		// though i think it being here makes more sense
		private Disk getBootDiskType(Vector<Disk> disks) {
			Disk boot = null;
			for( Disk d : disks ) {
				if (d.getBootDisk()) boot = d;
			}
			return boot;
		}

		private void reset() {
			hostname.reset();
			cores.reset();
			memory.reset();
			bootDisk.reset();
			dataDisk.reset();
			os.reset();
			app.reset();
			zone.reset();
			start.reset();
			duration.reset();
		}

		public Vector< HashMap<String, String> > getCommandStrings() {
			return buildCommandString(tier);
		}

		/**
		 * Returns a Map of key value pairs
		 * @return - a map of key value pair where key = hostname, value = command line args
		 */
		public Vector< HashMap<String,String> > buildCommandString(DomHelper.TIER_TYPE tier) {
			Vector<Machine> machines = dom.getMachineData(tier);
			Vector< HashMap<String, String> > commandStrings = new Vector< HashMap<String, String> >();
			Disk bootD = null;
			StringBuilder disks = new StringBuilder();
			StringBuilder apps = new StringBuilder();
			for(Machine r : machines) {
				hostname.setAttribute("hostname", projectId + r.getId());
				this.hostId = r.getId(); // sets the hostId for tagging this command string s.t. provisioning has a use for it
				cores.setAttribute("cores", r.getCores());
				memory.setAttribute("memory", r.getMemory());
				bootDisk.setAttribute("type", (bootD = getBootDiskType(r.getDisks())).getType());
				bootDisk.setAttribute("size", bootD.getSize());
				os.setAttribute("os", r.getOS());
				zone.setAttribute("zone", dom.getZoneData(tier));
				start.setAttribute("starttime", formatter.format(new java.util.Date()));
				duration.setAttribute("duration", new java.util.Date());
				for( Disk d : r.getDisks()) {
					if (!d.getBootDisk()) {
						dataDisk.setAttribute("type", d.getType());
						dataDisk.setAttribute("size", d.getSize());
						disks.append(dataDisk.toString());
						dataDisk.reset();
					}
				}
				for( Application a : r.getApps()) {
					app.setAttribute("app", a.getId());
					apps.append(app.toString());
					app.reset();
				}
				String temp = apps.toString();
				if (temp.length() > 0) // take into account no apps are installed during provisioning
					app.setAttribute("app", temp.substring(0, temp.length()-1)); // this is sooooo like C programming...really clumsy
				HashMap<String, String> kvPair = new HashMap<String, String>();
				kvPair.put(this.hostId, hostname.toString() + cores.toString() + memory.toString() + bootDisk.toString() + disks.toString() + os.toString() + app.toString() + zone.toString() + start.toString() + duration.toString());
				commandStrings.add(kvPair);
				reset();
			}
		return commandStrings;
	}
}


package de.cf.autoscaler.manager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import de.cf.autoscaler.api.binding.Binding;
import de.cf.autoscaler.applications.AppBlueprint;
import de.cf.autoscaler.applications.ScalableApp;
import de.cf.autoscaler.applications.ScalableAppService;
import de.cf.autoscaler.data.mongodb.AppBlueprintRepository;
import de.cf.autoscaler.exception.InvalidPolicyException;
import de.cf.autoscaler.exception.InvalidWorkingSetException;
import de.cf.autoscaler.exception.LimitException;
import de.cf.autoscaler.exception.SpecialCharacterException;
import de.cf.autoscaler.exception.TimeException;
import de.cf.autoscaler.kafka.KafkaPropertiesBean;
import de.cf.autoscaler.kafka.producer.ProtobufProducer;
import de.cf.autoscaler.kafka.producer.StringProducer;
import de.cf.autoscaler.properties.AutoscalerPropertiesBean;
import de.cf.autoscaler.properties.DefaultValueBean;

/**
 * Manager for adding, deleting and getting {@linkplain ScalableApp}.
 * @author Marius Berger
 *
 */
@Service
public class ScalableAppManager {

	/**
	 * Logger of this class.
	 */
	private Logger log = LoggerFactory.getLogger(ScalableAppManager.class);
	
	/**
	 * Property Bean for Kafka Settings.
	 */
	@Autowired
	private KafkaPropertiesBean kafkaProperties;
	
	/**
	 * Property Bean for default values.
	 */
	@Autowired
	private DefaultValueBean defaults;
	
	/**
	 * Properties for settings for the Autoscaler.
	 */
	@Autowired
	private AutoscalerPropertiesBean autoscalerProperties;
	
	/**
	 * Repository for connection to the database.
	 */
	@Autowired
	private AppBlueprintRepository appRepository;
	
	/**
	 * Producer to publish protobuf messages on Kafka.
	 */
	@Autowired
	private ProtobufProducer protobufProducer;
	
	/**
	 * Producer to publish String messages on Kafka.
	 */
	@Autowired
	private StringProducer stringProducer;
	
	/**
	 * Internal list of all {@linkplain ScalableApp} objects bound to the Autoscaler.
	 */
	private List<ScalableApp> apps;
	
	/**
	 * Basic constructor for setting up the manager.
	 */
	public ScalableAppManager() {
		apps = new ArrayList<ScalableApp>();
	}

	/**
	 * Connects to the database and loads the stored {@linkplain AppBlueprint} object
	 * to create {@linkplain ScalableApp} objects.
	 * This method will not overwrite existing objects with equal IDs or remove the current {@linkplain ScalableApp} objects.
	 */
	@PostConstruct
	public void loadFromDatabase() {
		log.info("Importing from database ...");
		List<AppBlueprint> appsFromDb = appRepository.findAll();
		
		for (int i = 0; i < appsFromDb.size(); i++) {		
			AppBlueprint bp = appsFromDb.get(i);
			
			try {
				ScalableAppService.isValid(bp);
				ScalableApp app = new ScalableApp(bp, kafkaProperties, autoscalerProperties, protobufProducer);
				if (!contains(app)) {
					add(app,true);
					log.info("Imported app from database: "+app.getIdentifierStringForLogs());
				} else {
					log.debug("Found an already existing binding with the same ID while trying to import " + bp.getBinding().getIdentifierStringForLogs());
				}
			} catch (LimitException | InvalidPolicyException | SpecialCharacterException | TimeException | InvalidWorkingSetException ex) {
				log.error("Found an invalid AppBlueprint while trying to synch with the database: "
							+bp.getBinding().getIdentifierStringForLogs()+" : "+ex.getMessage());
			}
		}
	}
	
	/**
	 * Adds a {@linkplain ScalableApp} to the list and the database, if its ID is not already taken.
	 * @param app {@linkplain ScalableApp} to add
	 * @param loadedFromDatabase boolean indicator to signal, whether this ScalableApp was loaded from the database.
	 * @return true if the application was successfully added
	 */
	public boolean add(ScalableApp app, boolean loadedFromDatabase) {
		if (!contains(app)) {
			apps.add(app);
			String action = StringProducer.LOADING;
			log.debug("Added following app to ScalableAppManager: "+app.getIdentifierStringForLogs());
			if (!loadedFromDatabase) {
				appRepository.save(app.getCopyOfBlueprint());
				action = StringProducer.CREATING;
				log.info("Bound following app: "+app.getIdentifierStringForLogs());
			}
			stringProducer.produceBinding(action, app.getBinding().getId(), app.getBinding().getResourceId(), app.getBinding().getScalerId());
			return true;
		}
		return false;
	}
	
	/**
	 * Removes a {@linkplain ScalableApp} from the list and the database, if an application with the same ID is found.
	 * @param app {@linkplain ScalableApp} to remove
	 * @return true if the application was successfully removed
	 */
	public boolean remove(ScalableApp app) {
		if (contains(app)) {
			apps.remove(app);
			appRepository.delete(app.getBinding().getId());
			stringProducer.produceBinding(StringProducer.DELETING, app.getBinding().getId(), app.getBinding().getResourceId(), app.getBinding().getScalerId());
			log.info("Removed following app from ScalableAppManager: "+app.getIdentifierStringForLogs());
			return true;
		}
		return false;
	}
	
	/**
	 * See {@linkplain #remove(ScalableApp)}
	 * @param bindingId id of the application to remove
	 * @return true if an application with the given binding id was successfully removed
	 */
	public boolean remove(String bindingId) {
		return remove(get(bindingId));
	}
	
	/**
	 * See {@linkplain #contains(ScalableApp)}
	 * @param bindingId ID of the application to look for
	 * @return true if the list contains an application with an id equal to the given one 
	 */
	public boolean contains(String bindingId) {
		for (int i = 0; i < apps.size(); i++) {
			if (apps.get(i).getBinding().getId().equals(bindingId)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Looks for an equal {@linkplain ScalableApp} in the list.
	 * @param app {@linkplain ScalableApp} to look for.
	 * @return true if the list contains an application equal to the given one
	 */
	public boolean contains(ScalableApp app) {
		return apps.contains(app);
	}
	
	/**
	 * Returns a {@linkplain ScalableApp} if a matching one was found
	 * @param bindingId ID of the application to look for
	 * @return {@linkplain ScalableApp} that matches the search criteria
	 */
	public ScalableApp get(String bindingId) {
		for (int i = 0 ; i < apps.size(); i++) {
			if (apps.get(i).getBinding().getId().equals(bindingId)) {
				return apps.get(i);
			}
		}
		return null;
	}
	
	public ScalableApp getByResourceId(String resourceId) {
		for (int i = 0 ; i < apps.size(); i++) {
			if (apps.get(i).getBinding().getResourceId().equals(resourceId)) {
				return apps.get(i);
			}
		}
		return null;
	}
	
	/**
	 * Returns a default {@linkplain ScalableApp} with the given binding information.
	 * @param binding binding information for the new {@linkplain ScalableApp}
	 * @return the new {@linkplain ScalableApp}
	 */
	public ScalableApp getNewApp(Binding binding) {
		return new ScalableApp(binding, kafkaProperties, defaults, autoscalerProperties, protobufProducer);
	}
	
	/**
	 * Returns the count of managed applications.
	 * @return count of managed applications.
	 */
	public int size() {
		return apps.size();
	}
	
	/**
	 * Creates and returns a new {@linkplain List} with the managed applications as a flat copy.
	 * @return flat copy of the managed applications as a {@linkplain List}
	 */
	public List<ScalableApp> getFlatCopyOfApps() {
		return new LinkedList<ScalableApp>(apps);
	}
	
	/**
	 * Creates and returns a {@linkplain List} with the identifier Strings of all managed applications.
	 * @return {@linkplain List} with the identifier Strings of all managed applications.
	 */
	public List<String> getListOfIdentifierStrings() {
		ScalableApp current;
		List<String> list = new LinkedList<String>();
		for (int i = 0; i < apps.size(); i++) {
			current = apps.get(i);
			try {
				current.acquire();
				list.add(current.getIdentifierStringForLogs());
			} catch (InterruptedException ex) {}
			current.release();
		}
		return list;
	}
	
	/**
	 * Creates and returns a {@linkplain List} with the basic information Strings of all managed applications.
	 * @return {@linkplain List} with the basic information Strings of all managed applications.
	 */
	public List<Binding> getListOfBindings() {
		ScalableApp current;
		List<Binding> list = new LinkedList<Binding>();
		
		for (int i = 0; i < apps.size(); i++) {
			current = apps.get(i);
			try {
				current.acquire();
				list.add(current.getBinding());
			} catch (InterruptedException ex) {}
			current.release();
		}
		
		return list;
	}

	/**
	 * Updates the {@linkplain AppBlueprint} of a {@linkplain ScalableApp} in the database.
	 * @param app {@linkplain ScalableApp} to update.
	 */
	public void updateInDatabase(ScalableApp app) {
		appRepository.save(app.getCopyOfBlueprint());
	}
}
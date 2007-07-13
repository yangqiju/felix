/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.dependencymanager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Service implementation.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class ServiceImpl implements Service {
    private static final Class[] VOID = new Class[] {};
	private static final ServiceRegistration NULL_REGISTRATION;
    private static final ServiceStateListener[] SERVICE_STATE_LISTENER_TYPE = new ServiceStateListener[] {};
    
    private final BundleContext m_context;

    // configuration (static)
    private String m_callbackInit;
    private String m_callbackStart;
    private String m_callbackStop;
    private String m_callbackDestroy;
    private Object m_serviceName;
    private Object m_implementation;
    
    // configuration (dynamic, but does not affect state)
    private Dictionary m_serviceProperties;
    
    // configuration (dynamic, and affects state)
    private ArrayList m_dependencies = new ArrayList();
    
    // runtime state (calculated from dependencies)
    private State m_state;
    
    // runtime state (changes because of state changes)
    private Object m_serviceInstance;
    private ServiceRegistration m_registration;

    // service state listeners
    private final List m_stateListeners = new ArrayList();

    // work queue
    private final SerialExecutor m_executor = new SerialExecutor();
    
    // instance factory
	private Object m_instanceFactory;
	private String m_instanceFactoryCreateMethod;
	
	// composition manager
	private Object m_compositionManager;
	private String m_compositionManagerGetMethod;
	private Object m_compositionManagerInstance;
    
    public ServiceImpl(BundleContext context) {
    	m_state = new State((List) m_dependencies.clone(), false);
        m_context = context;
        m_callbackInit = "init";
        m_callbackStart = "start";
        m_callbackStop = "stop";
        m_callbackDestroy = "destroy";
        m_implementation = null;
    }
    
    private void calculateStateChanges(final State oldState, final State newState) {
    	if (oldState.isWaitingForRequired() && newState.isTrackingOptional()) {
        	m_executor.enqueue(new Runnable() {
				public void run() {
					activateService(newState);
				}});
    	}
    	if (oldState.isTrackingOptional() && newState.isWaitingForRequired()) {
    		m_executor.enqueue(new Runnable() {
				public void run() {
					deactivateService(oldState);
				}});
    	}
    	if (oldState.isInactive() && (newState.isTrackingOptional())) {
    		m_executor.enqueue(new Runnable() {
				public void run() {
					activateService(newState);
				}});
    	}
    	if (oldState.isInactive() && (newState.isWaitingForRequired())) {
    		m_executor.enqueue(new Runnable() {
				public void run() {
					startTrackingRequired(newState);
				}});
    	}
    	if ((oldState.isWaitingForRequired()) && newState.isInactive()) {
    		m_executor.enqueue(new Runnable() {
				public void run() {
					stopTrackingRequired(oldState);
				}});
    	}
    	if ((oldState.isTrackingOptional()) && newState.isInactive()) {
    		m_executor.enqueue(new Runnable() {
				public void run() {
					deactivateService(oldState);
					stopTrackingRequired(oldState);
				}});
    	}
    	m_executor.execute();
    }
    
    public Service add(final Dependency dependency) {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            m_dependencies.add(dependency);
        }
        if (oldState.isTrackingOptional() || (oldState.isWaitingForRequired() && dependency.isRequired())) {
        	dependency.start(this);
        }
        synchronized (m_dependencies) {
            newState = new State((List) m_dependencies.clone(), !oldState.isInactive());
            m_state = newState;
            calculateStateChanges(oldState, newState);
        }
        return this;
    }

    public Service remove(Dependency dependency) {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            m_dependencies.remove(dependency);
        }
        if (oldState.isTrackingOptional() || (oldState.isWaitingForRequired() && dependency.isRequired())) {
        	dependency.stop(this);
        }
        synchronized (m_dependencies) {
            newState = new State((List) m_dependencies.clone(), !oldState.isInactive());
            m_state = newState;
        }
        calculateStateChanges(oldState, newState);
        return this;
    }

    public List getDependencies() {
        synchronized (m_dependencies) {
            return (List) m_dependencies.clone();
        }
    }
    
    public ServiceRegistration getServiceRegistration() {
        return m_registration;
    }
    
    public Object getService() {
        return m_serviceInstance;
    }
    
    public void dependencyAvailable(final Dependency dependency) {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            newState = new State((List) m_dependencies.clone(), !oldState.isInactive());
            m_state = newState;
        }
        calculateStateChanges(oldState, newState);
        if (newState.isTrackingOptional()) {
        	m_executor.enqueue(new Runnable() {
        		public void run() {
        			updateInstance(dependency);
        		}
        	});
        	m_executor.execute();
        }
    }

    public void dependencyChanged(final Dependency dependency) {
    	State state;
        synchronized (m_dependencies) {
        	state = m_state;
        }
        if (state.isTrackingOptional()) {
        	m_executor.enqueue(new Runnable() {
        		public void run() {
        			updateInstance(dependency);
        		}
        	});
        	m_executor.execute();
        }
    }
    
    public void dependencyUnavailable(final Dependency dependency) {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            newState = new State((List) m_dependencies.clone(), !oldState.isInactive());
            m_state = newState;
        }
        calculateStateChanges(oldState, newState);
        if (newState.isTrackingOptional()) {
        	m_executor.enqueue(new Runnable() {
        		public void run() {
        			updateInstance(dependency);
        		}
        	});
        	m_executor.execute();
        }
    }

    public synchronized void start() {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            newState = new State((List) m_dependencies.clone(), true);
            m_state = newState;
        }
        calculateStateChanges(oldState, newState);
    }

    public synchronized void stop() {
    	State oldState, newState;
        synchronized (m_dependencies) {
        	oldState = m_state;
            newState = new State((List) m_dependencies.clone(), false);
            m_state = newState;
        }
        calculateStateChanges(oldState, newState);
    }

    public synchronized Service setInterface(String serviceName, Dictionary properties) {
	    ensureNotActive();
	    m_serviceName = serviceName;
	    m_serviceProperties = properties;
	    return this;
	}

	public synchronized Service setInterface(String[] serviceName, Dictionary properties) {
	    ensureNotActive();
	    m_serviceName = serviceName;
	    m_serviceProperties = properties;
	    return this;
	}

	public synchronized Service setCallbacks(String init, String start, String stop, String destroy) {
	    ensureNotActive();
	    m_callbackInit = init;
	    m_callbackStart = start;
	    m_callbackStop = stop;
	    m_callbackDestroy = destroy;
	    return this;
	}

	public synchronized Service setImplementation(Object implementation) {
	    ensureNotActive();
	    m_implementation = implementation;
	    return this;
	}
	
	public synchronized Service setFactory(Object factory, String createMethod) {
	    ensureNotActive();
		m_instanceFactory = factory;
		m_instanceFactoryCreateMethod = createMethod;
		return this;
	}

	public synchronized Service setFactory(String createMethod) {
		return setFactory(null, createMethod);
	}
	
	public synchronized Service setComposition(Object instance, String getMethod) {
	    ensureNotActive();
		m_compositionManager = instance;
		m_compositionManagerGetMethod = getMethod;
		return this;
	}
	
	public synchronized Service setComposition(String getMethod) {
		return setComposition(null, getMethod);
	}

	public String toString() {
	    return "ServiceImpl[" + m_serviceName + " " + m_implementation + "]";
	}

	public synchronized Dictionary getServiceProperties() {
	    if (m_serviceProperties != null) {
	        return (Dictionary) ((Hashtable) m_serviceProperties).clone();
	    }
	    return null;
	}

	public synchronized void setServiceProperties(Dictionary serviceProperties) {
	    m_serviceProperties = serviceProperties;
	    if (isRegistered() && (m_serviceName != null)) {
	        m_registration.setProperties(calculateServiceProperties());
	    }
	}

	// service state listener methods
	public void addStateListener(ServiceStateListener listener) {
    	synchronized (m_stateListeners) {
		    m_stateListeners.add(listener);
    	}
    	// when we register as a listener and the service is already started
    	// make sure we invoke the right callbacks so the listener knows
    	State state;
    	synchronized (m_dependencies) {
    		state = m_state;
    	}
    	if (state.isTrackingOptional()) {
    		listener.starting(this);
    		listener.started(this);
    	}
	}

	public void removeStateListener(ServiceStateListener listener) {
    	synchronized (m_stateListeners) {
    		m_stateListeners.remove(listener);
    	}
	}

	void removeStateListeners() {
    	synchronized (m_stateListeners) {
    		m_stateListeners.clear();
    	}
	}

	private void stateListenersStarting() {
		ServiceStateListener[] list = getListeners();
		for (int i = 0; i < list.length; i++) {
			list[i].starting(this);
		}
	}

	private void stateListenersStarted() {
		ServiceStateListener[] list = getListeners();
		for (int i = 0; i < list.length; i++) {
			list[i].started(this);
		}
	}

	private void stateListenersStopping() {
		ServiceStateListener[] list = getListeners();
		for (int i = 0; i < list.length; i++) {
			list[i].stopping(this);
		}
	}

	private void stateListenersStopped() {
		ServiceStateListener[] list = getListeners();
		for (int i = 0; i < list.length; i++) {
			list[i].stopped(this);
		}
	}

	private ServiceStateListener[] getListeners() {
		synchronized (m_stateListeners) {
			return (ServiceStateListener[]) m_stateListeners.toArray(SERVICE_STATE_LISTENER_TYPE);
		}
	}

	private void activateService(State state) {
		String init, start;
		synchronized (this) {
			init = m_callbackInit;
			start = m_callbackStart;
		}
        // service activation logic, first we initialize the service instance itself
        // meaning it is created if necessary and the bundle context is set
        initService();
        // then we invoke the init callback so the service can further initialize
        // itself
        invoke(init);
        // now is the time to configure the service, meaning all required
        // dependencies will be set and any callbacks called
        configureService(state);
        // inform the state listeners we're starting
        stateListenersStarting();
        // start tracking optional services
        startTrackingOptional(state);
        // invoke the start callback, since we're now ready to be used
        invoke(start);
        // register the service in the framework's service registry
        registerService();
        // inform the state listeners we've started
        stateListenersStarted();
    }

    private void deactivateService(State state) {
    	String stop, destroy;
    	synchronized (this) {
    		stop = m_callbackStop;
    		destroy = m_callbackDestroy;
    	}
        // service deactivation logic, first inform the state listeners
        // we're stopping
        stateListenersStopping();
        // then, unregister the service from the framework
        unregisterService();
        // invoke the stop callback
        invoke(stop);
        // stop tracking optional services
        stopTrackingOptional(state);
        // inform the state listeners we've stopped
        stateListenersStopped();
        // invoke the destroy callback
        invoke(destroy);
        // destroy the service instance
        destroyService(state);
    }

    private void invoke(String name) {
        if (name != null) {
            // invoke method if it exists
            try {
                Class clazz = m_serviceInstance.getClass();
                while (clazz != null) {
                	try {
                	Method method = clazz.getDeclaredMethod(name, null);
	                	if (method != null) {
	                		method.setAccessible(true);
	                		method.invoke(m_serviceInstance, null);
	                		return;
	                	}
                	}
                	catch (NoSuchMethodException e) {
                		// ignore this, we keep searching if the method does not exist
                	}
                	clazz = clazz.getSuperclass();
                }
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    private void startTrackingOptional(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (!dependency.isRequired()) {
                dependency.start(this);
            }
        }
    }

    private void stopTrackingOptional(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (!dependency.isRequired()) {
                dependency.stop(this);
            }
        }
    }

    private void startTrackingRequired(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency.isRequired()) {
                dependency.start(this);
            }
        }
    }

    private void stopTrackingRequired(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency.isRequired()) {
                dependency.stop(this);
            }
        }
    }
    
    private Object createInstance(Class clazz) throws SecurityException, NoSuchMethodException, InstantiationException, IllegalAccessException {
		Constructor constructor = clazz.getConstructor(VOID);
		constructor.setAccessible(true);
        return clazz.newInstance();
    }

    void initService() {
    	if (m_serviceInstance == null) {
	        if (m_implementation instanceof Class) {
	            // instantiate
	            try {
	            	m_serviceInstance = createInstance((Class) m_implementation);
	            } 
	            catch (InstantiationException e) {
	                // TODO handle this exception
	                e.printStackTrace();
	            } 
	            catch (IllegalAccessException e) {
	                // TODO handle this exception
	                e.printStackTrace();
	            } catch (SecurityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (NoSuchMethodException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	        }
	        else {
	        	if (m_instanceFactoryCreateMethod != null) {
	        		Object factory = null;
		        	if (m_instanceFactory != null) {
		        		if (m_instanceFactory instanceof Class) {
		        			try {
								factory = createInstance((Class) m_instanceFactory);
							} 
		        			catch (InstantiationException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							} 
		        			catch (IllegalAccessException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							} catch (SecurityException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							} catch (NoSuchMethodException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
		        		}
		        		else {
		        			factory = m_instanceFactory;
		        		}
		        	}
		        	else {
		        		factory = null; // TODO!!!! where does the factory come from if not explicitly defined
		        		// could be the activator?
		        		// could be ???
		        	}
		        	if (factory == null) {
		        		throw new IllegalStateException("Factory cannot be null");
		        	}
		        	try {
						Method m = factory.getClass().getDeclaredMethod(m_instanceFactoryCreateMethod, null);
						m_serviceInstance = m.invoke(factory, null);
					} 
		        	catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	        	}
	        	if (m_implementation == null) {
	        		throw new IllegalStateException("Implementation cannot be null");
	        	}
	            m_serviceInstance = m_implementation;
	        }
	        // configure the bundle context
	        configureImplementation(BundleContext.class, m_context);
	        configureImplementation(ServiceRegistration.class, NULL_REGISTRATION);
    	}        
    }
    
    private void configureService(State state) {
        // configure all services (the optional dependencies might be configured
        // as null objects but that's what we want at this point)
        configureServices(state);
    }

    private void destroyService(State state) {
        unconfigureServices(state);
        m_serviceInstance = null;
    }
    
    private void registerService() {
        if (m_serviceName != null) {
            ServiceRegistrationImpl wrapper = new ServiceRegistrationImpl();
            m_registration = wrapper;
            configureImplementation(ServiceRegistration.class, wrapper);
            // service name can either be a string or an array of strings
            ServiceRegistration registration;
            
            // determine service properties
            Dictionary properties = calculateServiceProperties();
            
            // register the service
            try {
                if (m_serviceName instanceof String) {
                    registration = m_context.registerService((String) m_serviceName, m_serviceInstance, properties);
                }
                else {
                    registration = m_context.registerService((String[]) m_serviceName, m_serviceInstance, properties);
                }
                wrapper.setServiceRegistration(registration);
            }
            catch (IllegalArgumentException iae) {
                // set the registration to an illegal state object, which will make all invocations on this
                // wrapper fail with an ISE (which also occurs when the SR becomes invalid)
                wrapper.setIllegalState();
            }
        }
    }

	private Dictionary calculateServiceProperties() {
		Dictionary properties = new Properties();
		addTo(properties, m_serviceProperties);
		for (int i = 0; i < m_dependencies.size(); i++) {
			Dependency d = (Dependency) m_dependencies.get(i);
			if (d instanceof ConfigurationDependency) {
				ConfigurationDependency cd = (ConfigurationDependency) d;
				if (cd.isPropagated()) {
					Dictionary dict = cd.getConfiguration();
					addTo(properties, dict);
				}
			}
		}
		if (properties.size() == 0) {
			properties = null;
		}
		return properties;
	}

	private void addTo(Dictionary properties, Dictionary additional) {
		if (properties == null) {
			throw new IllegalArgumentException("Dictionary to add to cannot be null.");
		}
		if (additional != null) {
			Enumeration e = additional.keys();
			while (e.hasMoreElements()) {
				Object key = e.nextElement();
				properties.put(key, additional.get(key));
			}
		}
	}
    
    private void unregisterService() {
        if (m_serviceName != null) {
            m_registration.unregister();
            configureImplementation(ServiceRegistration.class, NULL_REGISTRATION);
        }
    }
    
    private void updateInstance(Dependency dependency) {
        if (dependency instanceof ServiceDependency) {
            ServiceDependency sd = (ServiceDependency) dependency;
            // update the dependency in the service instance (it will use
            // a null object if necessary)
            if (sd.isAutoConfig()) {
                configureImplementation(sd.getInterface(), sd.getService());
            }
        }
        else if (dependency instanceof ConfigurationDependency) {
        	ConfigurationDependency cd = (ConfigurationDependency) dependency;
        	if (cd.isPropagated()) {
        		// change service properties accordingly
        		Dictionary props = calculateServiceProperties();
        		m_registration.setProperties(props);
        	}
        }
    }
    
    /**
     * Configure a field in the service implementation. The service implementation
     * is searched for fields that have the same type as the class that was specified
     * and for each of these fields, the specified instance is filled in.
     * 
     * @param clazz the class to search for
     * @param instance the instance to fill in
     */
    private void configureImplementation(Class clazz, Object instance) {
    	Object[] instances = null;
    	if (m_compositionManagerGetMethod != null) {
			if (m_compositionManager != null) {
    			m_compositionManagerInstance = m_compositionManager;
    		}
    		else {
    			m_compositionManagerInstance = m_serviceInstance;
    		}
    		if (m_compositionManagerInstance != null) {
	    		try {
					Method m = m_compositionManagerInstance.getClass().getDeclaredMethod(m_compositionManagerGetMethod, null);
            		m.setAccessible(true);
					instances = (Object[]) m.invoke(m_compositionManager, null);
				} 
	    		catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
    		}
    	}
    	else {
    		instances = new Object[] { m_serviceInstance };
    	}
    	if (instances != null) {
	    	for (int i = 0; i < instances.length; i++) {
	    		Object serviceInstance = instances[i];
		        Class serviceClazz = serviceInstance.getClass();
		        while (serviceClazz != null) {
		            Field[] fields = serviceClazz.getDeclaredFields();
		            for (int j = 0; j < fields.length; j++) {
		                if (fields[j].getType().equals(clazz)) {
		                    try {
		                    	fields[j].setAccessible(true);
		                        // synchronized makes sure the field is actually written to immediately
		                        synchronized (new Object()) {
		                            fields[j].set(serviceInstance, instance);
		                        }
		                    }
		                    catch (Exception e) {
		                        System.err.println("Exception while trying to set " + fields[j].getName() +
		                            " of type " + fields[j].getType().getName() +
		                            " by classloader " + fields[j].getType().getClassLoader() +
		                            " which should equal type " + clazz.getName() +
		                            " by classloader " + clazz.getClassLoader() +
		                            " of type " + serviceClazz.getName() +
		                            " by classloader " + serviceClazz.getClassLoader() +
		                            " on " + serviceInstance + 
		                            " by classloader " + serviceInstance.getClass().getClassLoader() +
		                            "\nDumping stack:"
		                        );
		                        e.printStackTrace();
		                        System.out.println("C: " + clazz);
		                        System.out.println("I: " + instance);
		                        System.out.println("I:C: " + instance.getClass().getClassLoader());
		                        Class[] classes = instance.getClass().getInterfaces();
		                        for (int k = 0; k < classes.length; k++) {
		                            Class c = classes[k];
		                            System.out.println("I:C:I: " + c);
		                            System.out.println("I:C:I:C: " + c.getClassLoader());
		                        }
		                        System.out.println("F: " + fields[j]);
		                        throw new IllegalStateException("Could not set field " + fields[j].getName() + " on " + serviceInstance);
		                    }
		                }
		            }
		            serviceClazz = serviceClazz.getSuperclass();
		        }
	    	}
    	}
    }

    private void configureServices(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency instanceof ServiceDependency) {
                ServiceDependency sd = (ServiceDependency) dependency;
                if (sd.isAutoConfig()) {
                    configureImplementation(sd.getInterface(), sd.getService());
                }
                // for required dependencies, we invoke any callbacks here
                if (sd.isRequired()) {
                    sd.invokeAdded();
                }
            }
        }
    }
    
    private void unconfigureServices(State state) {
        Iterator i = state.getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency instanceof ServiceDependency) {
                ServiceDependency sd = (ServiceDependency) dependency;
                // for required dependencies, we invoke any callbacks here
                if (sd.isRequired()) {
                    sd.invokeRemoved();
                }
            }
        }
    }

    private void ensureNotActive() {
    	State state;
    	synchronized (m_dependencies) {
    		state = m_state;
    	}
    	if (!state.isInactive()) {
            throw new IllegalStateException("Cannot modify state while active.");
        }
    }
    boolean isRegistered() {
    	State state;
    	synchronized (m_dependencies) {
    		state = m_state;
    	}
        return (state.isTrackingOptional());
    }
    
    static {
        NULL_REGISTRATION = (ServiceRegistration) Proxy.newProxyInstance(ServiceImpl.class.getClassLoader(), new Class[] {ServiceRegistration.class}, new DefaultNullObject()); 
    }
}

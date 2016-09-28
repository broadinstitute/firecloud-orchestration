src/main/{packages}/
- dataaccess
	- DAOs, defined per artifact. For instance, one DAO for ES, one for Thurloe, one for mysql.
		- every DAO has an abstract parent, plus implementation classes for runtime instances.
			We will also have mock implementation classes that live in the src/test hierarchy
		- if a single DAO per artifact is too big, cut it up into multiple traits aggregated by a DAO class.
- model
	- model classes. Case classes if possible. Little-to-no logic implementations; things like toString allowed
- service
	- Actors that encapsulate business logic, defined per functional aspect.
		- Called by webservice; calls to DAOs
		- All methods should be testable; called by unit tests
		- For instance, there will be a Library service class, which may call both ES and Rawls DAOs
- webservice
	- route definitions
	- minimal-to-none business logic; everything testable should be in a service
	- calls service classes, using PerRequest
- startup class (Boot.scala, Agora.scala, Main.scala, etc - standardize name?)
	- instantiates runtime DAOs from config (group into an Application object)
	- defines the service constructors, which are used by webservices to create service instances
	- collects routes and starts webserver

src/main/{packages}/
- dataaccess
	- mocks for DAO implementations

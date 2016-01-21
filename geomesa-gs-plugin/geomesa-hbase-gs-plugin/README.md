# GeoMesa HBase GeoServer Plugin

The HBase GeoServer plugin is a shaded jar that contains HBase 1.0. To change HBase versions,
you would need to update the pom and re-build this module.

### Installation Instructions

After building, unzip the `target/geomesa-hbase-gs-plugin-<version>-install.zip` file into GeoServer's
WEB-INF/lib directory.

### Additional Resources

The HBase data store requires the configuration file hbase-site.xml to be on the classpath. This can
be accomplished by placing the file in geoserver/WEB-INF/classes (you should make the directory if it
doesn't exist).
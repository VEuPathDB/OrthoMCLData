/**
 * 
 */
package org.apidb.orthomcl.load.plugin;

import org.apache.log4j.Logger;

/**
 * @author xingao
 */
public class PluginLoader {

    private static final Logger logger = Logger.getLogger(PluginLoader.class);

    public static void main(String[] args) throws Exception {
        // validate the input
        if (args.length < 1) {
            System.err.println("usage: javaPlugin <plugin_class> [<plugin_args>...]");
            System.exit(-1);
        }
        String pluginClassName = args[0].trim();
        String[] pluginArgs = new String[args.length - 1];
        System.arraycopy(args, 1, pluginArgs, 0, pluginArgs.length);

        invokePlugin(pluginClassName, pluginArgs);
        
        System.exit(0);
    }

    public static void invokePlugin(String pluginClassName, String[] pluginArgs)
            throws Exception {
        logger.info("Invoking plugin " + pluginClassName + "...");
        
        // create an instance of the plugin
        Class<?> pluginClass = Class.forName(pluginClassName);
        Plugin plugin = (Plugin)pluginClass.getConstructor().newInstance();

        // invoke the plugin
        long start = System.currentTimeMillis();
        try {
            plugin.setArgs(pluginArgs);
            plugin.invoke();
        } catch (Exception ex) {
            throw ex;
        } finally {
            long end = System.currentTimeMillis();
            logger.info("Time spent: " + ((end - start) / 1000.0) + " seconds.");
        }
    }
}

/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.pgp;

import org.mule.api.ExceptionPayload;
import org.mule.api.MuleMessage;
import org.mule.api.config.MuleProperties;
import org.mule.module.client.MuleClient;
import org.mule.tck.junit4.FunctionalTestCase;
import org.mule.util.FileUtils;
import org.mule.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PGPSecurityFilterTestCase extends FunctionalTestCase
{
    protected static final String TARGET = "/encrypted.txt";
    protected static final String DIRECTORY = "output";
    protected static final String MESSAGE_EXCEPTION = "The required object/property \"UserId\" is null. Message payload is of type: String";

    @Override
    protected boolean isDisabledInThisEnvironment()
    {
        return (AbstractEncryptionStrategyTestCase.isCryptographyExtensionInstalled() == false);
    }

    @Override
    protected String getConfigResources()
    {
        return "test-pgp-encrypt-config.xml";
    }

    @Test
    public void testAuthenticationAuthorised() throws Exception
    {
        byte[] msg = loadEncryptedMessage();

        Map<String, String> props = new HashMap<String, String>();
        props.put("TARGET_FILE", TARGET);
        props.put(MuleProperties.MULE_USER_PROPERTY, "Mule server <mule_server@mule.com>");

        MuleClient client = new MuleClient(muleContext);
        MuleMessage reply = client.send("vm://echo", new String(msg), props);
        assertNull(reply.getExceptionPayload());

        //poll for the output file; wait for a max of 5 seconds
        File pollingFile = null;
        for(int i = 0; i < 5; i++)
        {
            pollingFile = new File(DIRECTORY + TARGET);
            if(!pollingFile.exists())
            {
                Thread.sleep(1000);
            }
        }
        pollingFile = null;

        try
        {
            // check if file exists
            FileReader outputFile = new FileReader(DIRECTORY + TARGET);
            String fileContents = IOUtils.toString(outputFile);
            outputFile.close();

            // see the GenerateTestMessage class for the content of the message
            assertTrue(fileContents.contains("This is a test message"));

            // delete file not to be confused with tests to be performed later
            File f = FileUtils.newFile(DIRECTORY + TARGET);
            assertTrue("Deleting the output file failed", f.delete());
        }
        catch (FileNotFoundException fileNotFound)
        {
            fail("File not successfully created");
        }
    }
    private byte[] loadEncryptedMessage() throws IOException
    {
        URL url = Thread.currentThread().getContextClassLoader().getResource("./encrypted-signed.asc");

        FileInputStream in = new FileInputStream(url.getFile());
        byte[] msg = IOUtils.toByteArray(in);
        in.close();

        return msg;
    }

    // see MULE-3672
    @Test
    public void testAuthenticationNotAuthorised() throws Exception
    {
        MuleClient client = new MuleClient(muleContext);

        MuleMessage reply = client.send("vm://echo", "An unsigned message", null);

        assertNotNull(reply.getExceptionPayload());
        ExceptionPayload excPayload = reply.getExceptionPayload();
        assertEquals(MESSAGE_EXCEPTION, excPayload.getMessage());
    }
}

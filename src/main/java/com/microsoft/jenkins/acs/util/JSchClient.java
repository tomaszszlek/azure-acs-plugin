/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.commands.IBaseCommandData;
import hudson.util.Secret;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * An SSH client used to talk to the remote server via JSch.
 */
public class JSchClient {
    private final String host;
    private final int port;
    private final String username;
    private final SSHUserPrivateKey credentials;
    private final IBaseCommandData context;

    private final JSch jsch;
    private final Session session;

    public JSchClient(
            final String host,
            final int port,
            @Nullable final String username,
            final SSHUserPrivateKey credentials,
            @Nullable final IBaseCommandData context) {
        this.host = host;
        this.port = port;
        String tmpUsername;
        if (StringUtils.isNotBlank(username)) {
            tmpUsername = username;
        } else {
            tmpUsername = credentials.getUsername();
        }
        this.username = tmpUsername;
        this.context = context;

        this.jsch = new JSch();
        this.credentials = credentials;
        final Secret userPassphrase = credentials.getPassphrase();
        String passphrase = null;
        if (userPassphrase != null) {
            passphrase = userPassphrase.getPlainText();
        }
        byte[] passphraseBytes = null;
        try {
            if (passphrase != null) {
                passphraseBytes = passphrase.getBytes(Constants.DEFAULT_CHARSET);
            }
            int seq = 0;
            for (String privateKey : credentials.getPrivateKeys()) {
                String name = this.username;
                if (seq++ != 0) {
                    name += "-" + seq;
                }
                jsch.addIdentity(name, privateKey.getBytes(Constants.DEFAULT_CHARSET), null, passphraseBytes);
            }

            this.session = jsch.getSession(this.username, this.host, this.port);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            this.session.setConfig(config);
            this.session.connect();
        } catch (JSchException | UnsupportedEncodingException e) {
            throw new IllegalArgumentException(Messages.JSchClient_failedToCreateSession(), e);
        }
    }

    public void copyTo(final File sourceFile, final String remotePath) throws JSchException {
        log(Messages.JSchClient_copyFileTo(sourceFile, host, remotePath));
        withChannelSftp(new ChannelSftpConsumer() {
            @Override
            public void apply(final ChannelSftp channel) throws JSchException, SftpException {
                channel.put(sourceFile.getAbsolutePath(), remotePath);
            }
        });
    }

    public void copyTo(final InputStream in, final String remotePath) throws JSchException {
        try {
            withChannelSftp(new ChannelSftpConsumer() {
                @Override
                public void apply(final ChannelSftp channel) throws JSchException, SftpException {
                    channel.put(in, remotePath);
                }
            });
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                log("Failed to close input stream: " + e.getMessage());
            }
        }
    }

    public void copyFrom(final String remotePath, final File destFile) throws JSchException {
        log(Messages.JSchClient_copyFileFrom(host, remotePath, destFile));
        withChannelSftp(new ChannelSftpConsumer() {
            @Override
            public void apply(final ChannelSftp channel) throws JSchException, SftpException {
                channel.get(remotePath, destFile.getAbsolutePath());
            }
        });
    }

    public void copyFrom(final String remotePath, final OutputStream out) throws JSchException {
        withChannelSftp(new ChannelSftpConsumer() {
            @Override
            public void apply(final ChannelSftp channel) throws JSchException, SftpException {
                channel.get(remotePath, out);
            }
        });
    }

    public void withChannelSftp(final ChannelSftpConsumer consumer) throws JSchException {
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            try {
                consumer.apply(channel);
            } catch (SftpException e) {
                throw new JSchException(Messages.JSchClient_sftpError(), e);
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public String execRemote(final String command) throws JSchException, IOException {
        return execRemote(command, true);
    }

    public String execRemote(final String command, final boolean showCommand) throws JSchException, IOException {
        ChannelExec channel = null;
        try {

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            if (showCommand) {
                log(Messages.JSchClient_execCommand(command));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[Constants.READ_BUFFER_SIZE];

            channel.connect();
            InputStream in = channel.getInputStream();

            if (context != null) {
                channel.setErrStream(context.jobContext().getTaskListener().getLogger(), true);
            }

            while (true) {
                do {
                    // blocks on IO
                    int len = in.read(buffer, 0, buffer.length);
                    if (len < 0) {
                        break;
                    }
                    output.write(buffer, 0, len);
                } while (in.available() > 0);

                if (channel.isClosed()) {
                    if (in.available() > 0) {
                        continue;
                    }
                    log(Messages.JSchClient_commandExitStatus(channel.getExitStatus()));
                    if (channel.getExitStatus() != 0) {
                        throw new RuntimeException(Messages.JSchClient_errorExecution(channel.getExitStatus()));
                    }
                    break;
                }
            }
            String serverOutput = output.toString(Constants.DEFAULT_CHARSET);
            log(Messages.JSchClient_output(serverOutput));
            return serverOutput;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(Messages.JSchClient_failedExecution(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * Forward another remote SSH port to local through the current client, and create a new client based on the local
     * port.
     *
     * This is used to deal with the Jenkins slave -&gt; ACS master -&gt; ACS agents connection, where
     * <ul>
     *     <li>ACS master is accessible through a public IP, and has information for all the agents included.</li>
     *     <li>ACS agents are not publicly accessible directly. They lie in the same subnet with the master node and
     *     only has some private IP in that subnet. There maybe some front-end load balancer for the agents but we
     *     cannot access a specific agent from the public network directly.</li>
     * </ul>
     *
     * In order to talk to the agents from public network, we connect to the master node, use SSH forwarding to open
     * a local port and forward the traffic through the SSH tunnel to the agent SSH service.
     *
     * @param remoteHost The agent private IP address.
     * @param remotePort The agent SSH service port, by default this is 22.
     * @return A new client to the agent node with the same credentials
     * @throws JSchException exception raised from JSch operations
     */
    public JSchClient forwardSSH(final String remoteHost, final int remotePort) throws JSchException {
        int localPort = session.setPortForwardingL(0, remoteHost, remotePort);
        return new JSchClient("127.0.0.1", localPort, username, credentials, context);
    }

    public void close() {
        if (this.session != null) {
            this.session.disconnect();
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    private void log(final String message) {
        if (context != null) {
            context.logStatus(message);
        }
    }

    private interface ChannelSftpConsumer {
        void apply(ChannelSftp channel) throws JSchException, SftpException;
    }
}
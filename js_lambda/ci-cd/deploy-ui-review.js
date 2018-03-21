function deploy(payload, ctx) {
    load('https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.4/js_lambda/gestalt-sdk.js');
    log("***** begin ui review app deploy ************\n");

    payload = JSON.parse( payload );
    ctx  = JSON.parse( ctx );

    META = get_meta(null, null); // don't use caller credentials, they may not be valid against the target meta instance
    log("[init] found meta: " + META.url);

    var tgt_org = find_org(get_env("TARGET_ORG"));
    if ( ! tgt_org ) {
        log("ERROR: could not find target org");
        return getLog();
    }

    var tgt_env = find_environment(tgt_org, get_env("TARGET_ENV"));
    if ( ! tgt_env ) {
        log("ERROR: could not find target environment");
        return getLog();
    }

    var tgt_provider = find_provider(tgt_org, get_env("TARGET_PROVIDER"));
    if ( ! tgt_provider ) {
        log("ERROR: could not find target provider");
        return getLog();
    }

    var gitlab_token = get_env("GITLAB_TOKEN");

    var env_slug   = payload.gitlab_env_slug;
    var git_ref    = payload.git_ref;
    var git_sha    = payload.git_sha;
    var git_author = payload.git_author;
    var image      = payload.image;

    if (!env_slug) {
        log("ERROR: missing gitlab environment slug argument 'gitlab_env_slug'");
        return getLog();
    }
    if (!image) {
        log("ERROR: missing image argument 'image'");
        return getLog();
    }

    var date = (new Date()).toString();
    var desc =
        "CI review app: \n" +
        "Time: " + date + "\n" +
        "Author: " + git_author + "\n" +
        "Git ref: " + git_ref + "\n" +
        "SHA: " + git_sha + "\n";

    var vhost = "ui-review-" + env_slug + ".test.galacticfog.com";

    log("Will deploy image " + image + " to provider " + tgt_provider.name + ' at url: https://' + vhost);

    var app;
    try {
        var payload = {
            name:        env_slug,
            description: desc,
            properties: {
                provider: {
                    id: tgt_provider.id
                },
                num_instances:  1,
                cpus:           0.1,
                memory:         64.0,
                disk:           0.0,
                container_type: "DOCKER",
                image:          image,
                network:        "BRIDGE",
                port_mappings:  [{
                    protocol: "tcp",
                    name: "web",
                    expose_endpoint: true,
                    container_port: 80
                }],
                env: {
                    META_API_URL: get_env("LOCAL_META_URL","https://meta.test.galacticfog.com"),
                    SEC_API_URL: get_env("LOCAL_SEC_URL","https://security.test.galacticfog.com")
                },
                labels: {
                    HAPROXY_GROUP: "external",
                    HAPROXY_0_VHOST: vhost,
                    HAPROXY_0_REDIRECT_TO_HTTPS: "true",
                    DEPLOYED_AT: date,
                    GIT_AUTHOR: git_author,
                    GIT_SHA: git_sha,
                    GIT_REF: git_ref,
                    REVIEW_APP: env_slug
                },
                force_pull :    true
            }
        };
        log("container update/create payload: " + JSON.stringify(payload), LoggingLevels.DEBUG);

        app = find_container_by_name(tgt_org, tgt_env, payload.name);
        if ( app ) {
            app = patch_container(tgt_org, tgt_env, app, [
                patch_replace("image", payload.properties.image),
                patch_replace("description", payload.description),
                patch_replace("labels", payload.properties.labels)
            ]);
        } else {
            app = create_container(tgt_org, tgt_env, payload);
        }
    } catch(err) {
        log("ERROR: error creating container: response code " + err);
        return getLog();
    }
    log("Created new container with id " + app.id);

    var gitlab_env = find_gitlab_environment(gitlab_token, env_slug);
    if ( gitlab_env ) {
        log("Calling back to GitLab to update environment url: " + gitlab_env.id);
        update_gitlab_environment(gitlab_env.id, gitlab_token, {
            external_url: "https://" + vhost
        });
    } else {
        log("WARNING: Could not locate GitLab environment in order to update external_url");
    }

    try {
        slack_path = get_env("SLACK_PATH");
        slack_url = "https://hooks.slack.com" + slack_path;
        _SLACK(slack_url, "_" + image + "_ deployed to review app\nhttps://" + vhost);
        log("posted message to slack");
    } catch (err) {
        log("Caught error posting message to slack");
        log(err);
    }

    log("\n***** done with UI review app deploy ************");
    return getLog();
}

function stop(args, ctx) {
    load('https://raw.githubusercontent.com/GalacticFog/lambda-examples/1.4/js_lambda/gestalt-sdk.js');
    log("***** begin UI review app stop ************\n");

    args = JSON.parse( args );
    ctx  = JSON.parse( ctx );

    META = get_meta(null, ctx.creds);
    log("[init] found meta: " + META.url);

    var tgt_org = find_org(get_env("TARGET_ORG"));
    if ( ! tgt_org ) {
        log("ERROR: could not find target org");
        return getLog();
    }

    var tgt_env = find_environment(tgt_org, get_env("TARGET_ENV"));
    if ( ! tgt_env ) {
        log("ERROR: could not find target environment");
        return getLog();
    }

    var env_slug   = args.gitlab_env_slug;

    log("Will delete deployment associated with gitlab environment " + env_slug);

    var tgt_cntr = find_container_by_name(tgt_org, tgt_env, env_slug);
    if ( tgt_cntr ) {
        delete_container(tgt_org, tgt_env, tgt_cntr);
    } else {
        log("did not find any deployments matching that name");
    }

    log("\n***** done with UI review app stop ************");
    return getLog();
}

function patch_replace(prop_path, value) {
    return {
        op: "replace",
        path: "/properties/" + prop_path,
        value: value
    }
}

function update_gitlab_environment(environment_id, token, payload) {
    var url = "https://gitlab.com/api/v4/projects/2251734/environments/" + environment_id;
    var pc = client.prepareConnect(url)
        .setMethod("PUT")
        .addHeader("PRIVATE-TOKEN", token);
    log("PUT " + url, LoggingLevels.DEBUG);
    if (payload) {
        pc = pc.setBody(JSON.stringify(payload)).addHeader("Content-Type", "application/json")
    }
    return _handleResponse(pc.execute().get());
}

function find_gitlab_environment(token, env_slug) {
    log("search for environment with " + env_slug);
    var url = "https://gitlab.com/api/v4/projects/2251734/environments?per_page=1000";
    var pc = client.prepareConnect(url)
        .setMethod("GET")
        .addHeader("PRIVATE-TOKEN", token);
    log("GET " + url, LoggingLevels.DEBUG);
    var envs = _handleResponse(pc.execute().get());
    for each (e in envs) if (e.slug == env_slug) return e;
    return null;
}

function _SLACK(url, message) {
    return _REST("POST", url, {
        text: message,
        mrkdwn: true
    });
}

function _REST(method, url, payload, async, fResponse) {
    var pc = client.prepareConnect(url)
        .setMethod(method);
    log(method + " " + url, LoggingLevels.DEBUG);
    if (payload) {
        pc = pc.setBody(JSON.stringify(payload)).addHeader("Content-Type", "application/json")
    }
    var _async = async ? async : false; // just being explicit that the default here is 'false'
    if (_async) {
        if (!fResponse) fResponse = new CompletableFuture();
        pc.execute(new org.asynchttpclient.AsyncCompletionHandler({
            onCompleted: function(response) {
                fResponse.complete(_handleResponse(response));
            }
        }));
        return fResponse;
    }
    return _handleResponse(pc.execute().get());
}
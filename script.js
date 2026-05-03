async function login() {
    const body =
        "username=" + document.getElementById("username").value +
        "&password=" + document.getElementById("password").value;

    const res = await fetch("/login", {
        method: "POST",
        body: body
    });

    alert(await res.text());
}

async function upload() {
    const file = document.getElementById("fileInput").files[0];

    await fetch("/upload", {
        method: "POST",
        body: file
    });

    loadFiles();
}

async function loadFiles() {
    const res = await fetch("/files");
    document.getElementById("files").innerHTML =
        await res.text();
}

loadFiles();
#!/usr/bin/env sh
# Function to install node based on the operating system
install_node() {
    case "$(uname -s)" in
        Darwin)
            echo "Mac OS detected."
            install_command="brew install nodejs npm"
            ;;
        Linux)
            echo "Linux detected."
            install_command="sudo apt-get update && sudo apt-get install nodejs npm"
            ;;
        *)
            echo "Unsupported operating system."
            exit 1
            ;;
    esac

    # Run the install command
    eval $install_command
}

# Call the install_node function

install_node
npm install
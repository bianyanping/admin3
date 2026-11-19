import request from '../utils/request';
import {BASE_URI} from "./base";

export function login(data: { username: string; password: string }) {
    return request({
        url: `${BASE_URI}/login`,
        method: 'post',
        data: data
    });
}

// 用户自助注册，注册成功后账号有效期为 1 天
export function register(data: { username: string; password: string }) {
    return request({
        url: `${BASE_URI}/register`,
        method: 'post',
        data: data
    });
}

export function logout() {
    return request({
        url: `${BASE_URI}/logout`,
        method: 'post',
    });
}
